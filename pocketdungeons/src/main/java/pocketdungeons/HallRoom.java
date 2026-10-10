package pocketdungeons;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.List;
import java.util.UUID;

/**
 * The Astrolabe Room (design pass 2026-10-09, Q1; PD-181): the first staging room of a trip. Instead of three
 * random doors it shows the dungeons of one act as a row of 1 by 2 doors ({@link HallLayout}), with an
 * astrolabe in the middle of the room. The owner turns the astrolabe (right-click; sneak to turn back) to
 * change the act; opening a door previews the dungeon behind it exactly as the three doors did, and the
 * lever commits.
 *
 * <p>Each door carries its dungeon in three places: the doormat in front of it is the dungeon's token block,
 * a sign standing on the mat names it, and the copper bulb over it says how it stands (lit copper to enter,
 * oxidized for a finished dungeon, dark with an iron door while locked). Selecting a door dims every other
 * bulb. All of it is mod furniture, redrawn by {@link #arm} whenever the room is stamped or the act turns,
 * and taken down by {@link #dismantle} when the door is committed.
 */
final class HallRoom {

    /** Tags the astrolabe's entities, so a re-arm can find and replace them. */
    static final String TAG = PocketDungeonsMod.MOD_ID + ".hall_part";
    /** The astrolabe's column, local to the staging room's origin. */
    private static final int CORE_X = 7;
    private static final int CORE_Z = 7;

    private HallRoom() {}

    static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!entity.entityTags().contains(TAG)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.SUCCESS;
            }
            turn(sp, sp.isShiftKeyDown());
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    // ---- when the room is a hall ----------------------------------------------------------------

    /**
     * Whether {@code record}'s staging room is the first one of a trip, before any floor is cleared and
     * before a dungeon is chosen: the only room the hall row stands in. Later floors keep their three doors.
     */
    static boolean isHallStaging(InstanceRecord record) {
        return record != null && PocketDungeonsConfig.hallEnabled() && record.inFloorLoop()
                && record.stagingCellOrigin != null && !record.interval.endlessMine
                && record.interval.dungeonId.isEmpty() && record.interval.floorIndex == 0;
    }

    /**
     * Arms the hall in a staging room that is being stamped or reset, if it is a first staging room. A new
     * lobby has no record yet, so a missing record counts as a first staging room.
     *
     * @return whether the hall row now stands (so the caller keeps its three default doors out)
     */
    static boolean armFor(ServerLevel level, BlockPos o, DoorMask.Direction wall, UUID owner) {
        if (!PocketDungeonsConfig.hallEnabled() || level == null || o == null || wall == null || owner == null
                || DungeonDefs.current().size() == 0) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(owner);
        if (record != null && !isHallStaging(record)) {
            return false;
        }
        arm(level.getServer(), level, o, wall, owner, record);
        return true;
    }

    // ---- drawing the room -----------------------------------------------------------------------

    /** Clears the default doors and draws the act's row, the signs, mats, bulbs and the astrolabe. */
    static void arm(MinecraftServer server, ServerLevel level, BlockPos o, DoorMask.Direction wall,
                    UUID owner, InstanceRecord record) {
        HallOffers.Hall hall = HallOffers.build(server, owner, record == null ? 0 : record.interval.hallAct);
        if (record != null) {
            record.interval.hallAct = hall.act();
        }
        RoomTemplateGenerator.clearSelectorDoors(level, o, wall);
        RoomTemplateGenerator.clearBulbs(level, o, wall);
        // The lever and its sign belong to a selected door: none stands until a door is chosen.
        RoomTemplateGenerator.clearHallLevers(level, o, wall);
        if (record != null) {
            record.interval.hallLeverAlong = 0;
        }

        int experimental = ExperimentalDungeon.current() != null
                && hall.specials().size() < HallLayout.MAX_SPECIALS ? 1 : 0;
        int[] alongs = HallOffers.absoluteAlongs(wall, hall.doors().size(), hall.specials().size() + experimental);
        for (int slot = 1; slot <= alongs.length; slot++) {
            int along = alongs[slot - 1];
            DungeonDef def = hall.dungeon(slot);
            HallLayout.Status status = hall.status(slot);
            boolean locked = def != null && status.locked();
            RoomTemplateGenerator.placeHallDoor(level, o, wall, along, def == null
                    ? RoomTemplateGenerator.HallDoorKind.EXPERIMENTAL
                    : locked ? RoomTemplateGenerator.HallDoorKind.LOCKED : RoomTemplateGenerator.HallDoorKind.OPEN);
            RoomTemplateGenerator.setHallBulb(level, o, wall, along, bulbFor(def, status, false, false));
            BlockState mat = matFor(def);
            if (mat != null) {
                RoomTemplateGenerator.placeHallMat(level, o, wall, along, mat);
            }
            RoomTemplateGenerator.placeHallSign(level, o, wall, along, signLines(def, status, hall));
        }
        placeAstrolabe(level, o, hall);
    }

    /** The absolute positions along the selector wall where a door, and so a bulb, stands right now. */
    static java.util.Set<Integer> bulbAlongs(MinecraftServer server, InstanceRecord record) {
        HallOffers.Hall hall = HallOffers.of(server, record);
        int experimental = ExperimentalDungeon.current() != null
                && hall.specials().size() < HallLayout.MAX_SPECIALS ? 1 : 0;
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (int along : HallOffers.absoluteAlongs(record.roomDungeonDoor, hall.doors().size(),
                hall.specials().size() + experimental)) {
            out.add(along);
        }
        return out;
    }

    /** Which bulb a door shows, given whether another door is selected and whether this one is. */
    static RoomTemplateGenerator.HallBulb bulbFor(DungeonDef def, HallLayout.Status status, boolean someoneSelected,
                                                  boolean thisSelected) {
        if (def != null && status.locked()) {
            return RoomTemplateGenerator.HallBulb.DARK;
        }
        if (someoneSelected && !thisSelected) {
            return RoomTemplateGenerator.HallBulb.DARK;
        }
        return status.state() == HallLayout.State.FINISHED
                ? RoomTemplateGenerator.HallBulb.PATINA : RoomTemplateGenerator.HallBulb.LIT;
    }

    /** Redraws every bulb for the record's current selection: the selected door lit, the rest dark. */
    static void paintBulbs(MinecraftServer server, ServerLevel level, InstanceRecord record) {
        HallOffers.Hall hall = HallOffers.of(server, record);
        int experimental = ExperimentalDungeon.current() != null
                && hall.specials().size() < HallLayout.MAX_SPECIALS ? 1 : 0;
        int[] alongs = HallOffers.absoluteAlongs(record.roomDungeonDoor, hall.doors().size(),
                hall.specials().size() + experimental);
        int selected = record.floor.selectedStep;
        for (int slot = 1; slot <= alongs.length; slot++) {
            RoomTemplateGenerator.setHallBulb(level, record.stagingCellOrigin, record.roomDungeonDoor,
                    alongs[slot - 1], bulbFor(hall.dungeon(slot), hall.status(slot), selected > 0, slot == selected));
        }
        // The DESCEND lever and its sign stand beside the selected door and nowhere else (owner, 2026-10-09).
        RoomTemplateGenerator.clearHallLevers(level, record.stagingCellOrigin, record.roomDungeonDoor);
        record.interval.hallLeverAlong = 0;
        if (selected >= 1 && selected <= alongs.length) {
            java.util.Set<Integer> occupied = new java.util.HashSet<>();
            for (int along : alongs) {
                occupied.add(along);
            }
            int leverAlong = HallLayout.leverAlongFor(alongs[selected - 1], occupied);
            if (leverAlong > 0) {
                RoomTemplateGenerator.placeHallLever(level, record.stagingCellOrigin, record.roomDungeonDoor, leverAlong);
                record.interval.hallLeverAlong = leverAlong;
            }
        }
    }

    /**
     * The DESCEND lever glints while a door is selected, so the next step is the thing the eye finds first
     * (owner, 2026-10-09). Runs a few times a second from the server tick; nothing stands to glint until
     * a door is chosen.
     */
    static void leverGlow(ServerLevel level, InstanceRecord record) {
        if (record.interval.hallLeverAlong <= 0 || record.stagingCellOrigin == null || record.roomDungeonDoor == null
                || !RunSession.canChooseDoor(record)) {
            return;
        }
        BlockPos lever = RoomTemplateGenerator.hallLeverPos(record.stagingCellOrigin, record.roomDungeonDoor,
                record.interval.hallLeverAlong);
        // Only while the lever is really standing and can still be pulled; once the trip starts it is a stale marker.
        if (!level.getBlockState(lever).is(net.minecraft.world.level.block.Blocks.LEVER)) {
            return;
        }
        double x = lever.getX() + 0.5;
        double y = lever.getY() + 0.5;
        double z = lever.getZ() + 0.5;
        level.sendParticles(ParticleTypes.END_ROD, x, y + 0.2, z, 2, 0.25, 0.25, 0.25, 0.01);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 3, 0.35, 0.35, 0.35, 0.05);
    }

    /** The doormat block for a dungeon: its token, or nothing for an operator's offer. */
    private static BlockState matFor(DungeonDef def) {
        if (def == null || def.tokenMat().isEmpty()) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(def.tokenMat())).defaultBlockState();
    }

    /** The sign lines: the dungeon's name, then how it stands. */
    private static Component[] signLines(DungeonDef def, HallLayout.Status status, HallOffers.Hall hall) {
        if (def == null) {
            return new Component[]{Component.literal("Special").withStyle(ChatFormatting.GOLD),
                    Component.literal("Offer").withStyle(ChatFormatting.WHITE)};
        }
        String name = def.name();
        Component first;
        Component second;
        switch (status.state()) {
            case FINISHED -> {
                // Owner, 2026-10-09: "Finished" reads as nothing left to do. The door is open to be played
                // again, so the sign says that; the oxidized bulb already says it has been done.
                first = Component.literal(name).withStyle(ChatFormatting.WHITE);
                second = Component.literal("Play again").withStyle(ChatFormatting.AQUA);
            }
            case CAPSTONE_LOCKED -> {
                first = Component.literal(name).withStyle(ChatFormatting.GRAY);
                second = Component.literal("Finish the act").withStyle(ChatFormatting.DARK_GRAY);
            }
            case COMPASS_LOCKED -> {
                first = Component.literal(name).withStyle(ChatFormatting.GRAY);
                second = Component.literal("Compass " + def.unlockLevel()).withStyle(ChatFormatting.DARK_GRAY);
            }
            default -> {
                first = Component.literal(name).withStyle(status.startHere() || status.capstoneReady()
                        ? ChatFormatting.GOLD : ChatFormatting.WHITE);
                second = Component.literal(status.startHere() ? "Start here"
                        : def.kind() == DungeonDef.Kind.CAPSTONE ? "Capstone" : "Compass " + def.unlockLevel())
                        .withStyle(ChatFormatting.GRAY);
            }
        }
        return new Component[]{first, second};
    }

    // ---- clicks and the doors' slots ---------------------------------------------------------------

    /** The door slot (1 based) at absolute position {@code along} of a hall staging room, or {@code null}. */
    static Integer slotAt(MinecraftServer server, InstanceRecord record, int along) {
        HallOffers.Hall hall = HallOffers.of(server, record);
        int experimental = ExperimentalDungeon.current() != null
                && hall.specials().size() < HallLayout.MAX_SPECIALS ? 1 : 0;
        int[] alongs = HallOffers.absoluteAlongs(record.roomDungeonDoor, hall.doors().size(),
                hall.specials().size() + experimental);
        for (int i = 0; i < alongs.length; i++) {
            if (alongs[i] == along) {
                return i + 1;
            }
        }
        return null;
    }

    // ---- the astrolabe -------------------------------------------------------------------------------

    private static void placeAstrolabe(ServerLevel level, BlockPos o, HallOffers.Hall hall) {
        clearAstrolabeEntities(level, o);
        BlockPos plinth = o.offset(CORE_X, 1, CORE_Z);
        level.setBlock(plinth, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:waxed_cut_copper"))
                .defaultBlockState(), 3);
        double cx = o.getX() + CORE_X + 0.5;
        double cz = o.getZ() + CORE_Z + 0.5;

        // The turning instrument: a clock held above the plinth, always facing the viewer.
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:item_display");
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:clock");
        stack.putInt("count", 1);
        item.put("item", stack);
        item.putString("billboard", "center");
        item.putString("item_display", "fixed");
        item.put("transformation", DungeonScreen.scaleTransformation(1.1f));
        spawn(level, EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND), item, cx, o.getY() + 2.6, cz);

        // The act, and which acts are open.
        CompoundTag text = new CompoundTag();
        text.putString("id", "minecraft:text_display");
        text.put("text", DungeonScreen.encodeText(level, actText(hall)));
        text.putString("billboard", "center");
        text.putBoolean("shadow", true);
        text.putString("alignment", "center");
        text.putInt("line_width", 240);
        text.put("transformation", DungeonScreen.scaleTransformation(1.0f));
        spawn(level, EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND), text, cx, o.getY() + 3.55, cz);

        // The click box.
        CompoundTag box = new CompoundTag();
        box.putString("id", "minecraft:interaction");
        box.putFloat("width", 1.2f);
        box.putFloat("height", 2.6f);
        box.putBoolean("response", true);
        spawn(level, EntityTypes.INTERACTION.create(level, EntitySpawnReason.COMMAND), box, cx, o.getY() + 1.0, cz);
    }

    /** {@code Act 2: The Deep} over a line of the five acts, the shown one in gold and the locked ones dark. */
    private static Component actText(HallOffers.Hall hall) {
        MutableComponent text = Component.literal(ActProgress.label(hall.act())).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n"));
        for (int act = DungeonDef.MIN_ACT; act <= DungeonDef.MAX_ACT; act++) {
            boolean open = hall.openActs().contains(act);
            String digit = act == hall.act() ? "[" + act + "]" : String.valueOf(act);
            text.append(Component.literal(digit + (act == DungeonDef.MAX_ACT ? "" : "  ")).withStyle(
                    act == hall.act() ? ChatFormatting.GOLD : open ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
        }
        return text;
    }

    private static void spawn(ServerLevel level, Entity entity, CompoundTag tag, double x, double y, double z) {
        if (entity == null) {
            return;
        }
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        ListTag rot = new ListTag();
        rot.add(FloatTag.valueOf(0.0f));
        rot.add(FloatTag.valueOf(0.0f));
        tag.put("Rotation", rot);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(entity.problemPath(), PocketDungeonsMod.LOG)) {
            ValueInput in = TagValueInput.create(reporter, level.registryAccess(), tag);
            entity.load(in);
        }
        level.addFreshEntity(entity);
    }

    private static void clearAstrolabeEntities(ServerLevel level, BlockPos o) {
        for (Entity entity : level.getEntitiesOfClass(Entity.class, CellGeometry.cellBounds(o),
                e -> e.entityTags().contains(TAG))) {
            entity.discard();
        }
    }

    /** Takes the astrolabe, its plinth and the hall's row down, when a door is committed or the room is left. */
    static void dismantle(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        clearAstrolabeEntities(level, o);
        RoomTemplateGenerator.clearHallLevers(level, o, wall);
        BlockPos plinth = o.offset(CORE_X, 1, CORE_Z);
        if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(plinth).getBlock()).getPath().equals("waxed_cut_copper")) {
            level.setBlock(plinth, RoomBuilder.AIR, 3);
        }
        RoomTemplateGenerator.clearHallRow(level, o, wall, true);
    }

    // ---- turning --------------------------------------------------------------------------------------

    /** Turns the astrolabe to the next open act (or the previous when {@code back}). Owner only. */
    static void turn(ServerPlayer player, boolean back) {
        MinecraftServer server = player.level().getServer();
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (server == null || record == null || !isHallStaging(record) || !RunSession.canChooseDoor(record)) {
            return;
        }
        if (!player.getUUID().equals(record.owner)) {
            Chime.refused(player);
            player.sendOverlayMessage(Component.literal("The trip owner chooses.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        HallOffers.Hall hall = HallOffers.of(server, record);
        int next = HallLayout.nextAct(hall.openActs(), hall.act(), back);
        if (next == hall.act()) {
            Chime.refused(player);
            player.sendOverlayMessage(Component.literal("Only one act is open.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        // Any open preview closes: it showed a dungeon of the act being left.
        Instances.clearPreview(level, record, true);
        record.floor.selectedStep = 0;
        record.interval.hallAct = next;
        arm(server, level, record.stagingCellOrigin, record.roomDungeonDoor, record.owner, record);
        DungeonScreen.updateDoor(level, record, DungeonScreen.idleContent(level, record.owner));
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f, 1.2f);
        level.sendParticles(ParticleTypes.END_ROD, record.stagingCellOrigin.getX() + CORE_X + 0.5,
                record.stagingCellOrigin.getY() + 2.6, record.stagingCellOrigin.getZ() + CORE_Z + 0.5,
                30, 0.4, 0.4, 0.4, 0.05);
        String label = ActProgress.label(next);
        for (UUID member : record.members.keySet()) {
            StaggeredTitle.show(server, member, Component.literal("Act " + next).withStyle(ChatFormatting.GOLD),
                    List.of(label.contains(": ") ? label.substring(label.indexOf(": ") + 2) : label),
                    ChatFormatting.GRAY);
        }
    }
}
