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

import java.util.EnumSet;
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

    /** The door screen's 8x2 backdrop spans Y=4..5; the text centers on Y=5. */
    private static final float DOOR_SCALE = 2.0f;
    private static final float ENGINE_SCALE = 1.0f;

    private static final String BILLBOARD_FIXED = "fixed";
    private static final float VIEW_RANGE = 2.0f;
    private static final int LINE_WIDTH = 200;
    private static final boolean SEE_THROUGH = false;

    private DungeonScreen() {}

    // ---- public entry points -------------------------------------------------

    /** Refreshes the door screen above the selector doors with {@code content}. */
    static void updateDoor(ServerLevel level, InstanceRecord record, Component content) {
        if (record.roomCellOrigin == null) {
            return;
        }
        DoorMask.Direction wall = record.roomDungeonDoor;
        show(level, record.roomCellOrigin, wall, 7.5, DOOR_SCALE, yawFor(wall), content);
    }

    /**
     * Refreshes the engine screen above the engine block. {@code viewer} is
     * the player whose fuel count the screen shows; {@code null} renders the
     * cost line without a count (a visit copy, or a stamp where no player is
     * standing there yet).
     */
    static void updateEngine(ServerLevel level, InstanceRecord record, ServerPlayer viewer) {
        if (record.roomCellOrigin == null) {
            return;
        }
        Component content = engineContent(viewer);
        DoorMask.Direction engineWall = RoomGeometry.leftOf(record.roomDungeonDoor);
        show(level, record.roomCellOrigin, engineWall, 7.0, ENGINE_SCALE, yawFor(engineWall), content);
    }

    // ---- the five door-screen contexts (plan 19.1) ---------------------------

    /** Context 1: no door selected. A tutorial that disappears on first interaction. */
    static Component idleContent() {
        return Component.literal("POCKET DUNGEONS").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\nRight-click a door to preview\nPull the lever to start"));
    }

    /** Context 2: a door is selected: offered level, theme, effective affixes. */
    static Component previewContent(ServerLevel level, UUID owner, int step) {
        MinecraftServer server = level.getServer();
        DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
        int offerLevel = Math.max(1, entry.keystoneLevel());
        Keystone.Offer[] offers = Keystone.offers(owner, offerLevel, entry.currentTheme(), entry.depth());
        Keystone.Offer offer = offers[Math.min(step - 1, offers.length - 1)];
        EnumSet<Affix> effective = AffixMath.effective(owner, offer.level(), offer.affixes());
        return Component.literal("KEYSTONE " + offer.level() + "\n"
                + themeName(offer.theme()) + "\n" + affixLine(effective));
    }

    /** Context 3: a run is in progress: level, theme, affixes, and the clock. */
    static Component runContent(InstanceRecord record) {
        String timeLine = record.timer == null
                ? "Untimed"
                : "Time: " + KeystoneMath.formatClock(record.timer.secondsRemaining());
        return Component.literal("KEYSTONE " + record.layout.keystoneLevel() + "\n"
                + themeName(record.theme) + "\n" + affixLine(record.affixes) + "\n" + timeLine);
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
        int visitors = Math.max(0, record.members.size() - 1);
        int whitelist = RoomWhitelist.forServer(server).get(record.owner).size();
        return Component.literal(title + "\nVisitors: " + visitors + "\nWhitelist: " + whitelist);
    }

    /** Context 5: the lever refused: "Select a door first", "Not enough fuel". */
    static Component refusalContent(String message) {
        return Component.literal(message).withStyle(ChatFormatting.RED);
    }

    // ---- engine screen content ----------------------------------------------

    /**
     * The engine screen's two lines: the viewer's fuel count (when there is a
     * viewer) and the per-premium-door cost. Fuel lives in the player's
     * inventory (M12), so the terminal is a view and a sink, never a store.
     */
    static Component engineContent(ServerPlayer viewer) {
        Item fuel = Fuel.item();
        String fuelName = fuel == null ? "fuel" : fuel.getName(ItemStack.EMPTY).getString();
        String first = viewer != null
                ? "FUEL: " + Fuel.count(viewer) + " " + fuelName
                : "Engine: " + fuelName;
        return Component.literal(first + "\nCost per premium door: "
                + PocketDungeonsConfig.fuelCostPerGreaterDoor());
    }

    // ---- summon / update / clear --------------------------------------------

    /** Clears any tagged screen near the anchor, then summons a fresh one. */
    private static void update(ServerLevel level, double[] xyz, float yaw, float scale, Component text) {
        clear(level, xyz);
        summon(level, xyz, yaw, scale, text);
    }

    private static void show(ServerLevel level, BlockPos roomOrigin, DoorMask.Direction wall,
                             double along, float scale, float yaw, Component content) {
        int lines = lines(content);
        // The text block's center sits (0.025 * scale * (5 * lines - 1)) blocks
        // below the entity position, and its top at (0.025 * scale) above it
        // (verified in TextDisplayRenderer's quad layout). Lifting the anchor
        // by that center offset centers the block on the screen's Y=5 row.
        double y = 5.0 + RENDER_SCALE * scale * (5 * lines - 1);
        update(level, wallAnchor(roomOrigin, wall, along, y), yaw, scale, content);
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
     * from {@code wall}. The renderer builds the plane normal from the yaw via
     * {@code rotationYXZ(-deg2rad(yaw))}: yaw 0 faces +Z, so the wall on the
     * +Z side (SOUTH) needs 180, the +X side (EAST) needs -90, and so on.
     */
    private static float yawFor(DoorMask.Direction wall) {
        return switch (wall) {
            case NORTH -> 0.0f;
            case SOUTH -> 180.0f;
            case EAST -> -90.0f;
            case WEST -> 90.0f;
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

    private static String affixLine(Set<Affix> affixes) {
        List<Affix> ordered = AffixMath.ordered(affixes);
        if (ordered.isEmpty()) {
            return "Oak";
        }
        StringBuilder sb = new StringBuilder();
        for (Affix affix : ordered) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(affix.label);
        }
        return sb.toString();
    }
}
