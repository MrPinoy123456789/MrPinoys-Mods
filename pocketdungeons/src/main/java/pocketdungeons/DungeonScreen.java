package pocketdungeons;

import com.mojang.serialization.DynamicOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M19 19.1/19.6: the two physical room screens, each a {@code text_display}
 * entity summoned and updated server-side. Follows Hearsay's {@code Bubbles}
 * pattern exactly (verified against the 26.2 jar): {@code see_through=false}
 * (MC-277982 renders see-through glyphs black), forced brightness
 * {@code {block: 15, sky: 15}}, the {@code text} tag encoded as an NBT object
 * via {@link ComponentSerialization#CODEC} rather than a JSON string (the
 * shape changed in 1.21.5), and an entity tag for orphan cleanup.
 *
 * <p>Billboard is {@code "fixed"} rather than Bubbles' {@code "center"}, and
 * the entity carries a {@code Rotation} matching the wall face: the fixed
 * billboard keeps the text plane parallel to the wall instead of swinging to
 * face the camera, and the yaw orients that plane so its readable face points
 * into the room. Text renders at 0.025x GUI scale per unit of transformation
 * scale, so the door screen runs at 2.0 (a 9px line becomes half a block:
 * the plan's "2 lines per block") and the engine screen at 1.0.
 *
 * <p>The screens are transient. They are summoned fresh at every room stamp
 * and re-summoned whenever their content changes ({@link #updateDoor},
 * {@link #updateEngine}); they are never captured with the room. The entity
 * sweeps on teardown and on {@code RoomStore.capture} discard them, which is
 * why every path that re-arms a room re-summons them.
 */
final class DungeonScreen {

    /** Marks both screens so orphaned ones can be found and killed. */
    static final String TAG = "pocketdungeons_screen";

    /** Text renders at 0.025x GUI size per unit of transformation scale. */
    private static final float RENDER_SCALE = 0.025f;

    /**
     * Where each screen's text centers vertically. The door screen's backdrop
     * is the two rows Y=4..5, so the seam between them is at 5.0; the engine
     * screen's is the single row Y=4, whose middle is 4.5.
     */
    private static final double DOOR_CENTER_Y = 5.0;
    private static final double ENGINE_CENTER_Y = 4.5;
    private static final double TRACKER_CENTER_Y = 4.5;
    private static final float DOOR_SCALE = 2.0f;
    private static final float ENGINE_SCALE = 1.0f;
    private static final float TRACKER_SCALE = 1.0f;

    private static final String BILLBOARD_FIXED = "fixed";
    private static final float VIEW_RANGE = 2.0f;
    /**
     * Where each screen's text centers along its wall. The door screen's
     * backdrop covers blocks 4..11, whose midpoint is the block boundary at
     * 8.0; the engine screen's covers 5..9, whose midpoint is the middle of
     * block 7 at 7.5.
     */
    private static final double DOOR_SCREEN_ALONG = 8.0;
    private static final double ENGINE_SCREEN_ALONG = 7.5;
    private static final double TRACKER_SCREEN_ALONG = 7.5;
    private static final int LINE_WIDTH = 200;
    private static final boolean SEE_THROUGH = false;

    private DungeonScreen() {}

    // ---- public entry points -------------------------------------------------

    /** Refreshes the door screen above the selector doors with {@code content}. */
    static void updateDoor(ServerLevel level, InstanceRecord record, Component content) {
        if (record.stagingCellOrigin == null) {
            return;
        }
        summonDoor(level, record.stagingCellOrigin, record.roomDungeonDoor, content);
    }

    /**
     * Summons the door screen for a room stamp, where no {@link InstanceRecord}
     * exists yet (stampLobby, createVisitInstance, completeDungeon all call
     * this with an origin and wall before or instead of a record).
     */
    static void summonDoor(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction wall, Component content) {
        show(level, roomOrigin, wall, DOOR_SCREEN_ALONG, DOOR_CENTER_Y, DOOR_SCALE,
                yawFor(wall), content);
    }

    /**
     * Refreshes the engine screen above the engine block. {@code viewer} is
     * the player whose fuel count the screen shows; {@code null} renders the
     * cost line without a count (a visit copy, or a stamp where no player is
     * standing there yet).
     */
    static void updateEngine(ServerLevel level, InstanceRecord record, ServerPlayer viewer) {
        if (record.stagingCellOrigin == null) {
            return;
        }
        Component content = engineContent(viewer);
        summonEngine(level, record.stagingCellOrigin, record.roomDungeonDoor, content);
    }

    /** Summons the engine screen for a room stamp; see {@link #updateEngine}. */
    static void summonEngine(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction selectorWall,
                             Component content) {
        DoorMask.Direction engineWall = RoomGeometry.leftOf(selectorWall);
        show(level, roomOrigin, engineWall, ENGINE_SCREEN_ALONG, ENGINE_CENTER_Y, ENGINE_SCALE,
                yawFor(engineWall), content);
    }

    /**
     * Refreshes the tracker screen on the wall opposite the engine screen
     * (the selector wall's right, the engine's left). Shows the owner's
     * active guided task, or once the tutorial is done, the weekly bounties.
     * Replaces the old scoreboard sidebar: instead of a global per-player
     * sidebar, the progress is a third physical screen in the room, visible
     * only to whoever is standing in it.
     */
    static void updateTracker(ServerLevel level, InstanceRecord record) {
        if (record.stagingCellOrigin == null) {
            return;
        }
        Component content = trackerContent(level.getServer(), record.owner);
        summonTracker(level, record.stagingCellOrigin, record.roomDungeonDoor, content);
    }

    /** Summons the tracker screen for a room stamp; see {@link #updateTracker}. */
    static void summonTracker(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction selectorWall,
                              Component content) {
        DoorMask.Direction trackerWall = RoomGeometry.rightOf(selectorWall);
        show(level, roomOrigin, trackerWall, TRACKER_SCREEN_ALONG, TRACKER_CENTER_Y, TRACKER_SCALE,
                yawFor(trackerWall), content);
    }

    /**
     * Refreshes the tracker screen for {@code owner}'s room from anywhere
     * with just a server (the task/bounty progress hooks, which fire without
     * a level or record in hand). No-op if the owner is not currently in a
     * dungeon instance, so progress earned outside a room does not summon a
     * screen into nothing.
     */
    static void refreshTracker(MinecraftServer server, UUID owner) {
        if (owner == null) {
            return;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(owner);
        if (record == null || record.stagingCellOrigin == null) {
            return;
        }
        ServerLevel dungeon = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (dungeon != null) {
            updateTracker(dungeon, record);
        }
    }

    // ---- the five door-screen contexts (plan 19.1) ---------------------------

    /**
     * Context 1: no door selected. A tutorial that disappears on first
     * interaction. {@code level} and {@code owner} are nullable; when both
     * are present and the owner is still at keystone level 1, the idle
     * screen instead shows the first-time-player prompt.
     */
    static Component idleContent(ServerLevel level, UUID owner) {
        MutableComponent content;
        if (level != null && owner != null
                && DungeonLog.forServer(level.getServer()).get(owner).keystoneLevel() <= 1) {
            content = Component.literal("POCKET DUNGEONS").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\nRight-click the Oak Door\nThen pull the lever to descend"));
        } else {
            content = Component.literal("POCKET DUNGEONS").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\nRight-click a door to preview\nPull the lever to start"));
        }
        return content;
    }

    /** Context 2: a door is selected: offered level, theme, effective affixes. */
    static Component previewContent(ServerLevel level, UUID owner, int step) {
        MinecraftServer server = level.getServer();
        DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
        int offerLevel = Math.max(1, entry.keystoneLevel());
        Keystone.Offer[] offers = Keystone.offers(owner, offerLevel, entry.currentTheme(), entry.depth());
        Keystone.Offer offer = offers[Math.min(step - 1, offers.length - 1)];
        Set<String> effective = AffixMath.effective(owner, offer.level(), offer.affixes(),
                AffixManifest.current().definitions());
        MutableComponent content = Component.literal("KEYSTONE " + offer.level() + "\n"
                + themeName(offer.theme()) + "\n").append(affixLine(effective));
        // M27 27.1: the caution indicator for an operator's fixed test offer.
        if (offer.tier() == Keystone.Tier.EXPERIMENTAL) {
            content.append(Component.literal("\nCAUTION: EXPERIMENTAL").withStyle(ChatFormatting.RED));
        }
        if (offerLevel <= 1) {
            content.append(Component.literal("\nPull the lever to descend!").withStyle(ChatFormatting.GREEN));
        }
        return content;
    }

    /** Context 3: a run is in progress: level, theme, affixes, and the clock. */
    static Component runContent(ServerLevel level, InstanceRecord record) {
        String timeLine = record.timer == null
                ? "Untimed"
                : "Time: " + KeystoneMath.formatClock(record.timer.secondsRemaining());
        MutableComponent content = Component.literal("KEYSTONE " + record.layout.keystoneLevel() + "\n"
                + themeName(record.theme) + "\n").append(affixLine(record.affixes))
                .append(Component.literal("\n" + timeLine));
        return content;
    }

    /**
     * Context 4: the room as a place, not a run. The room name and its
     * visibility are M20 fields (host-set name, {@code publicListed}); until
     * then the screen shows who the room belongs to, how many visitors are
     * inside, and the whitelist size.
     */
    static Component roomContent(MinecraftServer server, InstanceRecord record, ServerPlayer viewer) {
        String title;
        ServerPlayer ownerOnline = server.getPlayerList().getPlayer(record.owner);
        if (viewer != null && record.owner.equals(viewer.getUUID())) {
            title = "Your Room";
        } else if (ownerOnline != null) {
            title = ownerOnline.getName().getString() + "'s Room";
        } else {
            title = "A Friend's Room";
        }
        int visitors = record.owner != null && record.members.containsKey(record.owner)
                ? record.members.size() - 1
                : record.members.size(); // a visit instance never holds the owner
        int whitelist = RoomWhitelist.forServer(server).get(record.owner).size();
        return Component.literal(title + "\nVisitors: " + visitors + "\nWhitelist: " + whitelist);
    }

    /** Context 5: the lever refused: "Select a door first", "Not enough fuel". */
    static Component refusalContent(String message) {
        return Component.literal(message).withStyle(ChatFormatting.RED);
    }

    // ---- engine screen content ----------------------------------------------

    /**
     * The engine screen: a title, the viewer's banked balance (when there is a
     * viewer) and the per-premium-door cost.
     *
     * <p>The balance, not what the viewer is carrying. Fuel items are how fuel
     * travels; {@link Fuel#banked} is what a Greater door can actually spend,
     * and a screen that counted the stack in your pocket instead was reporting
     * the wrong number at exactly the moment you fed one in, when the two move
     * in opposite directions.
     *
     * <p>The title line is the other half of that: an unlabelled black panel
     * over a respawn anchor does not announce itself as the engine, so it says
     * so, in the same gold the door screen's idle title uses.
     */
    static Component engineContent(ServerPlayer viewer) {
        Item fuel = Fuel.item();
        String fuelName = fuel == null ? "fuel" : fuel.getName(ItemStack.EMPTY).getString();
        String balance = viewer != null
                ? "Stored: " + Fuel.banked(viewer) + " " + fuelName
                : "Accepts " + fuelName;
        return Component.literal("ECHO SHARDS").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n" + balance + "\nPer premium door: "
                        + PocketDungeonsConfig.fuelCostPerGreaterDoor()));
    }

    // ---- tracker screen content ---------------------------------------------

    /**
     * The tracker screen: the owner's active guided task with its progress,
     * or once the tutorial is done, the weekly bounties with their progress.
     * Replaces the old scoreboard sidebar, which was global and per-player;
     * this is a physical screen in the room, so it is visible only to whoever
     * is standing in it, and it carries no state outside the room.
     *
     * <p>Keys off the owner, not a viewer: a stamp with nobody standing there
     * still shows the owner's progress, the same way the engine screen's
     * cost line shows without a viewer. {@code server} or {@code owner} may
     * be null (a defensive caller), in which case just the title renders.
     */
    static Component trackerContent(MinecraftServer server, UUID owner) {
        if (server == null || owner == null) {
            return Component.literal("PROGRESS").withStyle(ChatFormatting.GOLD);
        }
        DungeonLog log = DungeonLog.forServer(server);
        int level = log.get(owner).keystoneLevel();
        Component taskLine = TaskTracker.taskLine(log, owner, level);
        if (taskLine != null) {
            return Component.literal("TASK").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("\n").append(taskLine));
        }
        MutableComponent content = Component.literal("WEEKLY BOUNTIES").withStyle(ChatFormatting.GOLD);
        List<Component> bountyLines = BountyTracker.bountyLines(log, owner);
        if (bountyLines == null || bountyLines.isEmpty()) {
            content.append(Component.literal("\nAll bounties done").withStyle(ChatFormatting.AQUA));
        } else {
            for (Component line : bountyLines) {
                content.append(Component.literal("\n")).append(line);
            }
        }
        return content;
    }

    // ---- summon / update / clear --------------------------------------------

    /**
     * Clears any tagged screen near {@code clearXyz}, then summons a fresh one
     * at {@code xyz}. The two points differ because the summon anchor drifts
     * with the new content's line count (see {@link #show}); clearing against
     * the content-independent point instead of the drifted one means a screen
     * left behind by some other content length is still found, not just the
     * one this exact content would have produced.
     */
    private static void update(ServerLevel level, double[] clearXyz, double[] xyz,
                               float yaw, float scale, Component text) {
        clear(level, clearXyz);
        summon(level, xyz, yaw, scale, text);
    }

    private static void show(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction wall,
                             double along, double centerY, float scale, float yaw,
                             Component content) {
        int lines = lines(content);
        // The text block hangs upward from the entity position, not downward:
        // the renderer scales by -0.025 (so its local +Y runs down the world)
        // and then translates the block by -(10 * lines - 1) before drawing
        // (verified in DisplayRenderer.TextDisplayRenderer's quad layout). Its
        // bottom edge lands 0.025 * scale below the anchor and its top edge
        // 0.025 * scale * (10 * lines - 1) above, so the center sits
        // 0.025 * scale * (5 * lines - 1) above. Dropping the anchor by that
        // much is what centers the block on the backdrop.
        double y = centerY - RENDER_SCALE * scale * (5 * lines - 1);
        double[] clearXyz = wallAnchor(roomOrigin, wall, along, centerY);
        double[] xyz = wallAnchor(roomOrigin, wall, along, y);
        update(level, clearXyz, xyz, yaw, scale, content);
    }

    private static void summon(ServerLevel level, double[] xyz, float yaw, float scale, Component text) {
        Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.put("text", encodeText(level, text));
        tag.putString("billboard", BILLBOARD_FIXED);
        tag.putFloat("view_range", VIEW_RANGE);
        tag.putBoolean("see_through", SEE_THROUGH);
        tag.putBoolean("shadow", true);
        tag.putBoolean("default_background", true);
        tag.putString("alignment", "center");
        tag.putByte("text_opacity", (byte) 255);
        tag.putInt("line_width", LINE_WIDTH);
        // Transformation as the 16-float matrix form of Transformation.EXTENDED_CODEC:
        // a uniform scale around the entity position (verified against the jar).
        tag.put("transformation", scaleTransformation(scale));

        CompoundTag bright = new CompoundTag();
        bright.putInt("block", 15);
        bright.putInt("sky", 15);
        tag.put("brightness", bright);

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(xyz[0]));
        pos.add(DoubleTag.valueOf(xyz[1]));
        pos.add(DoubleTag.valueOf(xyz[2]));
        tag.put("Pos", pos);

        ListTag rot = new ListTag();
        rot.add(FloatTag.valueOf(yaw));
        rot.add(FloatTag.valueOf(0.0f));
        tag.put("Rotation", rot);

        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);

        // Report load problems rather than discarding them; silently swallowing
        // these is what let the bubble colour bug hide for so long in Hearsay.
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(display.problemPath(), PocketDungeonsMod.LOG)) {
            ValueInput in = TagValueInput.create(reporter, level.registryAccess(), tag);
            display.load(in);
        }
        level.addFreshEntity(display);
    }

    /** Kills every tagged screen near the anchor, so a stale one cannot linger. */
    private static void clear(ServerLevel level, double[] xyz) {
        AABB box = new AABB(xyz[0] - 2, xyz[1] - 2, xyz[2] - 2,
                xyz[0] + 2, xyz[1] + 2, xyz[2] + 2);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box,
                e -> e.entityTags().contains(TAG))) {
            entity.discard();
        }
    }

    // ---- NBT helpers ---------------------------------------------------------

    /**
     * The {@code text} tag with the same codec {@code TextDisplay} reads it
     * back with, so the encoded shape is correct by construction. Since 1.21.5
     * this field is an NBT object, not a JSON string.
     */
    private static Tag encodeText(ServerLevel level, Component message) {
        DynamicOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return ComponentSerialization.CODEC.encodeStart(ops, message)
                .resultOrPartial(err -> PocketDungeonsMod.LOG.warn(
                        "Pocket Dungeons could not encode screen text: {}", err))
                .orElseGet(() -> StringTag.valueOf(message.getString()));
    }

    /** The 4x4 matrix form of a uniform scale, row-major as {@code MATRIX4F} expects. */
    private static ListTag scaleTransformation(float scale) {
        ListTag matrix = new ListTag();
        for (int i = 0; i < 16; i++) {
            int row = i >> 2;
            int col = i & 3;
            matrix.add(FloatTag.valueOf(row == col ? (row == 3 ? 1.0f : scale) : 0.0f));
        }
        return matrix;
    }

    /**
     * The entity position, half a block off the wall face so the fixed
     * billboard plane cannot z-fight with it.
     */
    private static double[] wallAnchor(BlockPos roomOrigin, DoorMask.Direction wall,
                                       double along, double y) {
        return switch (wall) {
            case NORTH -> new double[]{roomOrigin.getX() + along, roomOrigin.getY() + y, roomOrigin.getZ() + 1.1};
            case SOUTH -> new double[]{roomOrigin.getX() + along, roomOrigin.getY() + y,
                    roomOrigin.getZ() + RoomGeometry.CELL - 1.1};
            case EAST -> new double[]{roomOrigin.getX() + RoomGeometry.CELL - 1.1,
                    roomOrigin.getY() + y, roomOrigin.getZ() + along};
            case WEST -> new double[]{roomOrigin.getX() + 1.1, roomOrigin.getY() + y, roomOrigin.getZ() + along};
        };
    }

    /**
     * The yaw that turns a fixed-billboard text plane to face into the room
     * from {@code wall}. The plane's readable normal is the entity's own
     * facing, so this is plain vanilla yaw: 0 faces +Z (south), 90 faces -X
     * (west), 180 faces -Z (north), 270 faces +X (east). A screen on the wall
     * at the +Z side of the room (SOUTH) has to face north, one on the +X side
     * (EAST) has to face west, and so on: each is the opposite of the wall it
     * hangs on.
     */
    private static float yawFor(DoorMask.Direction wall) {
        return switch (wall) {
            case NORTH -> 0.0f;
            case SOUTH -> 180.0f;
            case EAST -> 90.0f;
            case WEST -> 270.0f;
        };
    }

    private static int lines(Component text) {
        String s = text.getString();
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static String themeName(String theme) {
        if (theme == null || theme.isEmpty()) {
            return "Uncharted";
        }
        ThemeManifest.Entry entry = ThemeManifest.current().byId(theme);
        return entry == null ? theme : entry.meta().name;
    }

    /**
     * The affix line for a door or run screen. The OMINOUS affix is coloured
     * {@link ChatFormatting#DARK_PURPLE} to match the chat message at run
     * start, so a sound-off player has a visible environmental signal that
     * the run is ominous even if they missed the chat line (M67 Q5).
     */
    private static Component affixLine(Set<String> affixes) {
        List<AffixDefinition> ordered = AffixMath.ordered(affixes,
                AffixManifest.current().definitions());
        if (ordered.isEmpty()) {
            return Component.literal("Oak");
        }
        MutableComponent line = Component.empty();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                line.append(Component.literal(", "));
            }
            AffixDefinition def = ordered.get(i);
            MutableComponent label = Component.literal(def.label);
            if (def.id.equals(AffixIds.OMINOUS)) {
                label.withStyle(ChatFormatting.DARK_PURPLE);
            }
            line.append(label);
        }
        return line;
    }
}
