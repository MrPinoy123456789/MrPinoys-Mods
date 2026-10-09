package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Room-template generator. Builds each room in a scratch region of the void
 * dimension, captures it with {@link StructureTemplate#fillFromWorld}, and
 * writes the .nbt file to
 * {@code src/main/resources/data/pocketdungeons/structure/rooms/}.
 *
 * <p>Entity capture is deferred by one tick so the level's spatial index has
 * time to pick up anything freshly spawned (see docs/PLAN.md's round-trip note).
 *
 * <h2>The library</h2>
 *
 * <p>The room set is a <strong>coverage floor</strong> plus a <strong>flavour
 * layer</strong>. The floor is one template per door-mask family -- dead end,
 * straight, corner, tee, cross -- each carrying all three content roles
 * ({@code encounter}, {@code loot}, {@code corridor}). Because a mask's four
 * rotations cover all four of its rotational variants, those five templates plus
 * the two single-role end caps satisfy every {@code (mask, role)} pair the
 * planner can ask for, which is what takes plan resolution from M3's measured 0%
 * to effectively 100%. Flavour rooms are themed variants at higher weight; they
 * only ever improve on the floor, never replace its guarantee.
 *
 * <p>Floor rooms are authored with <em>both</em> a chest and spawn points, and
 * {@code RoomContent} activates one or the other at stamp time from the cell's
 * role. That is why role is not baked into a template.
 *
 * <h2>The doorway lane rule</h2>
 *
 * <p>A doorway only obstructs near its own wall, so: keep {@code x} in
 * {@code [7,8]} clear for {@code z} in {@code [1,3]} and {@code [12,14]}, and
 * {@code z} in {@code [7,8]} clear for {@code x} in {@code [1,3]} and
 * {@code [12,14]}. The middle of the room is free to decorate. Every furniture
 * position below is chosen against that rule.
 */
final class RoomTemplateGenerator {

    static final int CELL = RoomGeometry.CELL;
    static final int WALL_HEIGHT = RoomGeometry.WALL_HEIGHT;
    private static final int CEILING_Y = RoomGeometry.CEILING_Y;
    private static final int STORY_HEIGHT = RoomGeometry.STORY_HEIGHT;
    private static final Vec3i TEMPLATE_SIZE = new Vec3i(CELL, CEILING_Y + 1, CELL);

    /**
     * M61: the capture size for a room of vertical span {@code spanY}. The y
     * extent grows by one {@link #STORY_HEIGHT} per extra story; x and z are
     * always one cell. A one story room gets {@link #TEMPLATE_SIZE} back.
     */
    static Vec3i templateSize(int spanY) {
        if (spanY <= 1) {
            return TEMPLATE_SIZE;
        }
        return new Vec3i(CELL, CEILING_Y + 1 + RoomGeometry.storyOffset(spanY), CELL);
    }

    // Shell palette lives on RoomBuilder now (M9 C2); this class used to carry
    // its own copy of all five constants.

    private static final Identifier DOOR = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "door");
    /** Marks a mob spawn point. Read out of the template at stamp time, then erased
     *  by {@link JigsawFallback} exactly like a door is. */
    private static final Identifier SPAWN = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "spawn");
    private static final Identifier TIER_1_LOOT = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "chests/tier_1");

    private static final int BASE_Y = 64;
    private static final int SCRATCH_X = 0;
    private static final int SCRATCH_Z = 1000000;
    /** Room-to-room spacing in the scratch region; two cells of slack. */
    private static final int SCRATCH_PITCH = 32;

    /** All four horizontal walls, for the rooms that carry a door on each. */
    static final Set<Direction> HORIZONTALS = EnumSet.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    /** The four common spawn points. This set is closed under rotation, which
     *  makes the {@code stamptest} cross-check easy to read. */
    static final BlockPos[] QUAD_SPAWNS = {
            new BlockPos(4, 1, 4), new BlockPos(11, 1, 4),
            new BlockPos(4, 1, 11), new BlockPos(11, 1, 11)
    };

    private static final class Task {
        final Runnable action;
        int delay;
        Task(Runnable action, int delay) { this.action = action; this.delay = delay; }
    }
    private static final List<Task> queue = new ArrayList<>();

    private RoomTemplateGenerator() {}

    /** Registers the server-tick drain for deferred development-time template captures. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Iterator<Task> it = queue.iterator(); it.hasNext(); ) {
                Task task = it.next();
                if (--task.delay <= 0) {
                    it.remove();
                    task.action.run();
                }
            }
        });
    }

    static void generate(ServerLevel level) {
        generate(level, null);
    }

    /**
     * Queues every room template, or with {@code only} set just the one of
     * that name, for capture.
     *
     * @return how many templates were queued
     */
    static int generate(ServerLevel level, String only) {
        Path outDir = templateOutDir();
        if (outDir == null) {
            return 0;
        }

        int queued = 0;
        int anomalies = 0;
        List<RoomSpec> specs = specs();
        for (int i = 0; i < specs.size(); i++) {
            if (only == null || only.equals(specs.get(i).name)) {
                buildAndQueue(level, outDir, specs.get(i), i * SCRATCH_PITCH);
                queued++;
            }
        }

        List<RoomSpec> anomalySpecs = anomalySpecs();
        for (int i = 0; i < anomalySpecs.size(); i++) {
            if (only == null || only.equals(anomalySpecs.get(i).name)) {
                buildAndQueue(level, outDir, anomalySpecs.get(i), (specs.size() + i) * SCRATCH_PITCH);
                queued++;
                anomalies++;
            }
        }

        PocketDungeonsMod.LOG.info("Queued {} pocketdungeons room templates for capture ({} anomaly)",
                queued, anomalies);
        return queued;
    }

    /**
     * The directory room templates are written to, created on demand. In a
     * dev environment this is the source tree so generated templates ship in
     * the next build. On a production server this is a datapack inside the
     * world's datapacks directory, so a {@code /reload} after generation
     * makes them available without a restart.
     *
     * @return the directory, or {@code null} if it could not be created
     */
    static Path templateOutDir() {
        // Dev environment: game dir's parent is the mod project.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Path outDir = FabricLoader.getInstance().getGameDir().getParent().resolve(
                    "src/main/resources/data/" + PocketDungeonsMod.MOD_ID + "/structure/rooms");
            try {
                Files.createDirectories(outDir);
            } catch (IOException e) {
                PocketDungeonsMod.LOG.error("Could not create template output directory", e);
                return null;
            }
            return outDir;
        }
        // Production: write to a datapack in the world's datapacks directory.
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path datapackDir = gameDir.resolve("world/datapacks/pocketdungeons_rooms"
                + "/data/" + PocketDungeonsMod.MOD_ID + "/structure/rooms");
        try {
            Files.createDirectories(datapackDir);
            // Write a pack.mcmeta so the game recognises the datapack.
            Path packMeta = gameDir.resolve("world/datapacks/pocketdungeons_rooms/pack.mcmeta");
            if (!Files.exists(packMeta)) {
                Files.createDirectories(packMeta.getParent());
                Files.writeString(packMeta,
                        "{\"pack\":{\"pack_format\": 0,\"description\":\"Pocket Dungeons generated room templates\"}}");
            }
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not create template datapack directory", e);
            return null;
        }
        return datapackDir;
    }

    // ---- the library --------------------------------------------------------

    /**
     * M45: the room library, assembled from one list per situation family. The
     * base set is everything that shipped before the situations round; the rest
     * are the catalogue families, each owning its own file so four template
     * milestones can add rooms without meeting in this one.
     */
    private static List<RoomSpec> specs() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.addAll(BaseRoomSpecs.list());
        specs.addAll(TraversalSpecs.list());
        specs.addAll(MechanismSpecs.list());
        specs.addAll(KnowledgeSpecs.list());
        specs.addAll(PressureSpecs.list());
        specs.addAll(SpurSpecs.list());
        specs.addAll(StagingSpecs.list());
        specs.addAll(SituationSpecs.list());
        specs.addAll(ResourceBiomeSpecs.list());
        specs.addAll(ActOneRoomSpecs.list());
        specs.addAll(CapstoneSpecs.list());
        specs.addAll(FinalFloorEarlySpecs.list());
        specs.addAll(FinalFloorLateSpecs.list());
        specs.addAll(GroveAndResourceSpecs.list());
        specs.addAll(CowWardSpecs.list());
        specs.addAll(CopperWorksRoomSpecs.list());
        specs.addAll(FrostworksRoomSpecs.list());
        specs.addAll(DeepslateRoomSpecs.list());
        specs.addAll(EnderArchiveRoomSpecs.list());
        specs.addAll(GenericHallVariantSpecs.list());
        specs.addAll(FinalFloorSpecs.list());
        specs.addAll(NetherEndSpecs.list());
        return specs;
    }

    /**
     * M35: the anomaly room set. Same coverage-floor shapes (dead end, straight,
     * corner) so the injection roll in {@code RoomSelector} can satisfy whatever
     * mask and role the swapped critical-path cell needs, but stamped in a shell
     * palette ({@link RoomBuilder#SANDSTONE}, {@link RoomBuilder#DEEPSLATE},
     * {@link RoomBuilder#ALEXS_ROOM}) foreign to every run theme rather than the
     * usual stone brick, plus one wall torch at a height no other room in the
     * set uses. The blocks are all real and already shipped for the M24 shell
     * system; only their placement, here, is wrong.
     */
    private static List<RoomSpec> anomalySpecs() {
        List<RoomSpec> specs = new ArrayList<>();

        specs.add(new RoomSpec("anomaly_alex_room", EnumSet.of(Direction.NORTH))
                .palette(RoomBuilder.ALEXS_ROOM)
                .chests(new BlockPos(2, 1, 2))
                .spawns(new BlockPos(4, 1, 11), new BlockPos(11, 1, 11))
                .decor((level, o) -> placeWallTorch(level, o.offset(14, 2, 12), Direction.WEST)));

        specs.add(new RoomSpec("anomaly_sandstone_corner", EnumSet.of(Direction.NORTH, Direction.EAST))
                .palette(RoomBuilder.SANDSTONE)
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> placeWallTorch(level, o.offset(1, 1, 8), Direction.EAST)));

        specs.add(new RoomSpec("anomaly_deepslate_straight", EnumSet.of(Direction.WEST, Direction.EAST))
                .palette(RoomBuilder.DEEPSLATE)
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> placeWallTorch(level, o.offset(8, 4, 1), Direction.SOUTH)));

        return specs;
    }

    /** A wall torch at a fixed position, for the anomaly set's one deliberately
     *  misplaced fixture: every other light in a cell comes from the ceiling
     *  lamps, so a torch on the wall itself has no reason to be there. */
    private static void placeWallTorch(ServerLevel level, BlockPos pos, Direction facing) {
        RoomBuilder.set(level, pos, Blocks.WALL_TORCH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing));
    }

    /**
     * The three door ids by identifier rather than through {@code Blocks},
     * because {@code Blocks.COPPER_DOOR} is a {@code WeatheringCopperCollection},
     * not a {@code Block}, and indexing it is more trouble than it is worth. This
     * is the visual language for affixes and is expected to grow: copper and its
     * four oxidation states are the obvious ladder when a fourth and fifth affix
     * arrive.
     */
    private static final Identifier DOOR_NONE = Identifier.parse("minecraft:oak_door");
    private static final Identifier DOOR_OMINOUS = Identifier.parse("minecraft:crimson_door");
    private static final Identifier DOOR_GREATER_3 = Identifier.parse("minecraft:exposed_copper_door");

    // ---- M19 furniture -------------------------------------------------------

    /** Unlit by default; the blockstate writes in {@link #setBulb} flip LIT. */
    private static final BlockState BULB = BuiltInRegistries.BLOCK.getValue(
            Identifier.parse("minecraft:copper_bulb")).defaultBlockState();
    private static final BlockState BULB_LIT = BuiltInRegistries.BLOCK.getValue(
            Identifier.parse("minecraft:copper_bulb")).defaultBlockState()
            .setValue(CopperBulbBlock.LIT, true);
    /** The physical backdrop behind each screen's text_display. */
    private static final BlockState SCREEN_BLOCK = Blocks.CONCRETE.black().defaultBlockState();
    /**
     * The door screen backdrop, rows Y=4..5: blocks 3..12, ten wide and centred
     * on the block boundary at 8.0 like the eight wide one it replaced, so the
     * two sheets of the door board (the floor info and the deal) sit side by
     * side on it. Block 2 (the go-home bulb, one of them mirrored to 13) stays
     * clear on every wall.
     */
    static final int DOOR_SCREEN_ALONG_MIN = 3;
    static final int DOOR_SCREEN_ALONG_MAX = 12;
    /** The commit lever base state; {@link #leverState} adds the wall-facing. */
    private static final BlockState LEVER_OFF = Blocks.LEVER.defaultBlockState()
            .setValue(LeverBlock.FACE, AttachFace.WALL);
    /** The label above the lever; {@link #signState} adds the wall-facing. */
    private static final BlockState LEVER_SIGN = Blocks.OAK_WALL_SIGN.defaultBlockState();
    /**
     * What the lever's sign says. One word, because the sign is read at a
     * glance from across the room while the player is deciding, and because
     * the door screen two rows up already carries the sentences.
     */
    private static final String LEVER_SIGN_WORD = "DESCEND";
    /**
     * Which of a sign's four lines the word goes on. Vanilla centers each line
     * horizontally on its own, so this is the whole of the centering: line 1 is
     * the upper of the two middle lines, which reads as centered with the other
     * three left empty.
     */
    private static final int LEVER_SIGN_LINE = 1;

    /**
     * Where the three selector doors stand along their wall: the 2-wide slot
     * itself ({@link RoomGeometry#DOOR_MIN}..{@code DOOR_MAX}) plus one block of
     * plain wall beside it, so the strip is contiguous.
     */
    private static final int[] SELECTOR_DOORS = {7, 8, 9};

    /**
     * The bulb row's height, in the wall ring rather than in the door row in
     * front of it: the selector doors fill Y=1..2 of the door row, so Y=3 of
     * the wall behind them is the course directly on top, and the door screen
     * starts at Y=4 right above that. Set into the wall, the bulbs are also
     * exactly the course the punched doorway's lintel occupies, which is why
     * {@link #clearBulbs} can hand them back to plain stone brick the moment
     * a door is chosen.
     */
    private static final int BULB_Y = 3;

    /** Where the commit lever stands, just past the third door, and its sign above it. */
    private static final int LEVER_ALONG = 10;
    private static final int SIGN_Y = 3;

    /**
     * The go-home control, on the selector wall left of the doors: a second
     * lever in the door row at along 2 with its own sign, and set into the
     * wall at along 3..5 a 3x3 screen, with a copper bulb in the wall over
     * the lever. Only a cleared floor's staging room has one
     * ({@link #placeHomeControl}); the lever asks, then banks the interval
     * and takes the party home, and the bulb lights once the interval has
     * run its usual length. {@link RoomProtection#isFurniture} protects the
     * same positions.
     *
     * <p>Playtest 2026-09-29 (A5): the lever used to stand at along 5, which
     * on a mirrored wall (the doors keep their absolute slot) put it right
     * beside door 3, and a player reaching for a door pulled it and ended
     * the interval. The lever now stands at the far end with the screen
     * between it and the doors: at least three blocks of screen between
     * the lever and the nearest door on every wall. Not along 1: on a NORTH selector wall that is in front of
     * the wall lodestone.
     */
    static final int HOME_LEVER_ALONG = 2;
    static final int HOME_SCREEN_ALONG_MIN = 3;
    static final int HOME_SCREEN_ALONG_MAX = 5;
    static final int HOME_SCREEN_Y_MIN = 1;
    static final int HOME_SCREEN_Y_MAX = 3;
    static final int HOME_BULB_ALONG = 2;
    static final int HOME_BULB_Y = 4;
    /** The go-home lever's sign: a verb, like DESCEND (playtest 2026-09-27, A5). */
    private static final String HOME_SIGN_WORD = "GO HOME";
    /** Once the dungeon is finished the haul is already banked, so the lever only leaves (PD-179, Q10a). */
    private static final String LEAVE_SIGN_WORD = "LEAVE";

    /**
     * The floor history board on the wall to the left of the selector wall
     * (2026-10-02, replacing the echo shard engine): a black panel along 4..11,
     * rows 2..5, behind {@link FloorHistory}'s text. Y=6 is the ceiling, so 5
     * is the top row.
     */
    static final int HISTORY_ALONG_MIN = 4;
    static final int HISTORY_ALONG_MAX = 11;
    static final int HISTORY_Y_MIN = 2;
    static final int HISTORY_Y_MAX = 5;

    /**
     * The bulb sitting over selector door {@code step} (1, 2 or 3). Callers
     * hold a step, not a position along the wall, and the two are not the same
     * number: {@link #setBulb} wants the latter.
     */
    static int bulbAlongForStep(int step) {
        return SELECTOR_DOORS[Math.clamp(step, 1, SELECTOR_DOORS.length) - 1];
    }

    static void placeSelectorDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        // Three doors standing one block in front of the wall that will become the
        // dungeon entrance once a choice is made. They cover the eventual 2-wide
        // opening and one block of plain wall beside it; removing all three on
        // choice and opening the slot turns that middle section into the doorway.
        // Deliberately not in the wall itself: the slot there is sealed solid
        // until a dungeon exists behind it, and a door is not a seal.
        //
        // Doors only. The leave-pad is authored at fixed local coordinates and so
        // is only correct at rotation 0; a room re-stamped behind the terminal
        // cell arrives at whatever rotation puts its 'ee' side facing back, and
        // carries its own pad in the blob already.
        Direction facing = CellGeometry.facingIntoRoom(wall);
        Identifier[] blocks = {DOOR_NONE, DOOR_OMINOUS, DOOR_GREATER_3};
        for (int i = 0; i < SELECTOR_DOORS.length; i++) {
            placeDoor(level, selectorDoorPos(o, wall, SELECTOR_DOORS[i]), blocks[i], facing,
                    DoorHingeSide.LEFT);
        }
    }

    /** Clears the three selector doors placed by {@link #placeSelectorDoors}, and the Astrolabe Room's row if one stood. */
    static void clearSelectorDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        for (int pos : SELECTOR_DOORS) {
            BlockPos lower = selectorDoorPos(o, wall, pos);
            RoomBuilder.set(level, lower, Blocks.AIR.defaultBlockState());
            RoomBuilder.set(level, lower.above(), Blocks.AIR.defaultBlockState());
        }
        clearHallRow(level, o, wall, false);
    }

    // ---- the Astrolabe Room's door row (design pass 2026-10-09, Q1) -------------------------------

    private static final Identifier DOOR_LOCKED = Identifier.parse("minecraft:iron_door");
    private static final BlockState BULB_OXIDIZED_LIT = BuiltInRegistries.BLOCK.getValue(
            Identifier.parse("minecraft:oxidized_copper_bulb")).defaultBlockState()
            .setValue(CopperBulbBlock.LIT, true);
    private static final Identifier SIGN_BLOCK = Identifier.parse("minecraft:oak_sign");

    /** What a hall door is made of: an oak door, an iron door while it is locked, a crimson door for an operator's offer. */
    enum HallDoorKind { OPEN, LOCKED, EXPERIMENTAL }

    /** A hall bulb's look: dark, lit copper, or lit oxidized copper (a finished dungeon wears patina). */
    enum HallBulb { DARK, LIT, PATINA }

    /** Every absolute position along the wall a hall door or a default selector door may stand on. */
    static int[] hallCandidateAlongs(DoorMask.Direction wall) {
        int[] out = new int[SELECTOR_DOORS.length + HallLayout.DOOR_SPACES.length + HallLayout.SPECIAL_SPACES.length];
        int n = 0;
        for (int along : SELECTOR_DOORS) {
            out[n++] = along;
        }
        for (int rel : HallLayout.DOOR_SPACES) {
            out[n++] = viewerAlong(wall, rel);
        }
        for (int rel : HallLayout.SPECIAL_SPACES) {
            out[n++] = viewerAlong(wall, rel);
        }
        return out;
    }

    /** Whether {@code along} is a place a hall door can stand on this wall. */
    static boolean isHallAlong(DoorMask.Direction wall, int along) {
        return HallLayout.isHallAlong(RoomGeometry.mirrorsAlong(wall), along);
    }

    /** The lower half of the hall door at {@code along}. */
    static BlockPos hallDoorPos(BlockPos o, DoorMask.Direction wall, int along) {
        return selectorDoorPos(o, wall, along);
    }

    /** One block in front of the hall door, at height {@code y}: where its doormat (y 0) and name sign (y 1) go. */
    static BlockPos hallFrontPos(BlockPos o, DoorMask.Direction wall, int along, int y) {
        return doorPlanePos(o, wall, along, y).relative(CellGeometry.facingIntoRoom(wall));
    }

    static void placeHallDoor(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, HallDoorKind kind) {
        Identifier block = switch (kind) {
            case OPEN -> DOOR_NONE;
            case LOCKED -> DOOR_LOCKED;
            case EXPERIMENTAL -> DOOR_OMINOUS;
        };
        placeDoor(level, hallDoorPos(o, wall, along), block, CellGeometry.facingIntoRoom(wall), DoorHingeSide.LEFT);
    }

    /** Sets the bulb over the hall door at {@code along}. */
    static void setHallBulb(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, HallBulb bulb) {
        RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), switch (bulb) {
            case DARK -> BULB;
            case LIT -> BULB_LIT;
            case PATINA -> BULB_OXIDIZED_LIT;
        });
    }

    /** The doormat: the dungeon's token block in the floor in front of its door. */
    static void placeHallMat(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, BlockState mat) {
        RoomBuilder.set(level, hallFrontPos(o, wall, along, 0), mat);
    }

    /** The name sign standing on the doormat, facing into the room, waxed so it cannot be edited. */
    static void placeHallSign(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, Component[] lines) {
        BlockPos pos = hallFrontPos(o, wall, along, 1);
        Direction facing = CellGeometry.facingIntoRoom(wall);
        int rotation = switch (facing) {
            case SOUTH -> 0;
            case WEST -> 4;
            case NORTH -> 8;
            default -> 12;
        };
        BlockState state = BuiltInRegistries.BLOCK.getValue(SIGN_BLOCK).defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16, rotation);
        RoomBuilder.set(level, pos, state);
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            PocketDungeonsMod.LOG.warn("The hall sign at {} did not come with a block entity", pos);
            return;
        }
        SignText text = new SignText().setColor(DyeColor.WHITE).setHasGlowingText(true);
        for (int i = 0; i < Math.min(4, lines.length); i++) {
            text = text.setMessage(i, lines[i]);
        }
        sign.setText(text, true);
        sign.setWaxed(true);
        sign.setChanged();
    }

    /**
     * Takes the Astrolabe Room's row out: every candidate door (air), the bulb above it (back to wall), the
     * sign in front of it (air) and the doormat (back to floor). {@code bulbsToo} false leaves the bulb course
     * alone, for the callers that clear the doors on their own.
     */
    static void clearHallRow(ServerLevel level, BlockPos o, DoorMask.Direction wall, boolean bulbsToo) {
        BlockState wallBlock = RoomBuilder.shellWallAt(level, o);
        for (int along : hallCandidateAlongs(wall)) {
            if (!isHallAlong(wall, along) && !(along >= 7 && along <= 9)) {
                continue;
            }
            BlockPos lower = hallDoorPos(o, wall, along);
            if (isHallAlong(wall, along)) {
                // Only the hall's own places: the default doors at 7 to 9 are the caller's.
                RoomBuilder.set(level, lower, RoomBuilder.AIR);
                RoomBuilder.set(level, lower.above(), RoomBuilder.AIR);
                if (level.getBlockState(hallFrontPos(o, wall, along, 1)).getBlock() instanceof net.minecraft.world.level.block.StandingSignBlock) {
                    RoomBuilder.set(level, hallFrontPos(o, wall, along, 1), RoomBuilder.AIR);
                    level.removeBlockEntity(hallFrontPos(o, wall, along, 1));
                }
                if (!level.getBlockState(hallFrontPos(o, wall, along, 0)).equals(RoomBuilder.FLOOR)) {
                    RoomBuilder.set(level, hallFrontPos(o, wall, along, 0), RoomBuilder.FLOOR);
                }
            }
            if (bulbsToo) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), wallBlock);
            }
        }
    }

    /**
     * Takes selector door {@code step} (2 or 3) back out and hands its bulb
     * position back to plain wall, for a door the owner's key cannot reach
     * yet ({@code Instances.hideLockedDoors}). The wall behind is already
     * sealed, so the gap reads as wall, not as a missing door.
     */
    static void hideSelectorDoor(ServerLevel level, BlockPos o, DoorMask.Direction wall, int step) {
        int along = bulbAlongForStep(step);
        BlockPos lower = selectorDoorPos(o, wall, along);
        RoomBuilder.set(level, lower, Blocks.AIR.defaultBlockState());
        RoomBuilder.set(level, lower.above(), Blocks.AIR.defaultBlockState());
        RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), RoomBuilder.shellWallAt(level, o));
    }

    /**
     * The post-selection double doors (M18 9.2): two vanilla wooden doors side
     * by side filling the freshly punched 2-wide doorway (Y=1..2), with a wall
     * lintel sealing the top (Y=3) so nothing can shoot or climb over them.
     * Placed by {@code Instances.generateBehindLobby} the moment a door is
     * chosen; from then on they are plain vanilla doors the player opens by
     * hand, since {@code Instances.selectorDoorStep} no longer claims clicks
     * once the run is underway, and closed doors stop mobs walking into the
     * room. The hinge side alternates so the two doors meet in the middle like
     * a real double door.
     */
    static void placePostSelectionDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        Direction facing = CellGeometry.facingIntoRoom(wall);
        for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
            BlockPos lower = postSelectionDoorPos(o, wall, i);
            DoorHingeSide hinge = i == RoomGeometry.DOOR_MIN
                    ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            placeDoor(level, lower, DOOR_NONE, facing, hinge);
            // The lintel matches the room's current shell, not a hardcoded stone
            // brick: a swapped shell (M24) would otherwise keep a stone-brick
            // strip above the doors every time the doors are re-placed.
            RoomBuilder.set(level, lower.offset(0, 2, 0), RoomBuilder.shellWallAt(level, o)); // lintel
        }
    }

    /** The lower half of the post-selection door at {@code along}, in the wall itself. */
    private static BlockPos postSelectionDoorPos(BlockPos o, DoorMask.Direction wall, int along) {
        return switch (wall) {
            case NORTH -> o.offset(along, 1, 0);
            case SOUTH -> o.offset(along, 1, RoomGeometry.CELL - 1);
            case EAST -> o.offset(RoomGeometry.CELL - 1, 1, along);
            case WEST -> o.offset(0, 1, along);
        };
    }

    /**
     * Restores the punched door slot to open air, removing the post-selection
     * double doors and their lintel. Capture hygiene (trap 17 in
     * {@code DISCOVERIES.md}): the doors are run-scoped mod furniture, not part
     * of the room, so they must not bake into the owner's blob. Callers clear
     * them before {@link RoomStore#capture} and put them straight back.
     */
    static void clearPostSelectionDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        for (BlockPos pos : CellGeometry.doorSlotPositions(o, wall)) {
            RoomBuilder.set(level, pos, Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * The lower half of the selector door at {@code along}, one block inside the
     * room from {@code wall} -- {@link Instances#selectorDoorStep} inverts this,
     * so the two must agree.
     */
    private static BlockPos selectorDoorPos(BlockPos o, DoorMask.Direction wall, int along) {
        return switch (wall) {
            case NORTH -> o.offset(along, 1, 1);
            case SOUTH -> o.offset(along, 1, RoomGeometry.CELL - 2);
            case EAST -> o.offset(RoomGeometry.CELL - 2, 1, along);
            case WEST -> o.offset(1, 1, along);
        };
    }

    /** A two-block-tall door, both halves matching. Facing is cosmetic only --
     *  every click here is intercepted before vanilla ever opens it. */
    private static void placeDoor(ServerLevel level, BlockPos lower, Identifier blockId,
                                  net.minecraft.core.Direction facing, DoorHingeSide hinge) {
        Block block = BuiltInRegistries.BLOCK.getValue(blockId);
        BlockState lowerState = block.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.OPEN, false);
        RoomBuilder.set(level, lower, lowerState);
        RoomBuilder.set(level, lower.above(), lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /**
     * The room's wall terminal: a single lodestone set into the north wall at
     * eye height (local x=1, y=2, z=0), part of the immutable shell. M18 moved
     * it there from the floor (NW corner): it is the terminal the player
     * right-clicks to open the mod navigation menu (M21), not a pad to stand
     * on, so it needs to be reachable at eye height. Authored at a fixed local
     * position rather than the rotation-invariant centre {@link #placeExitPad}
     * uses, exactly like the old pad: the blob carries it through capture and
     * re-placement. The north wall is clear of the selector-door wall and of
     * every door slot at every rotation of the template (see the M18 plan
     * notes), so the lodestone never collides with a doorway.
     */
    static void placeWallLodestone(ServerLevel level, BlockPos o) {
        RoomBuilder.set(level, o.offset(1, 2, 0), Blocks.LODESTONE.defaultBlockState());
    }

    /**
     * Where the run storage chest sits along its wall: clear of the doorway
     * lane and the window band (6 to 9) and of the lodestone's corner (1).
     */
    static final int STORAGE_ALONG = 12;
    static final int STORAGE_Y = 1;

    /**
     * The wall the run storage chest is set into: right of the selector wall,
     * across the room from the floor history board. The selector wall, the
     * history wall and the doorway back to the safe room take the other three.
     */
    static DoorMask.Direction storageWall(DoorMask.Direction selectorWall) {
        return CellGeometry.opposite(RoomGeometry.leftOf(selectorWall));
    }

    /** The run storage chest's position in a staging room whose selector doors face {@code selectorWall}. */
    static BlockPos runStoragePos(BlockPos o, DoorMask.Direction selectorWall) {
        return wallRingPos(o, storageWall(selectorWall), STORAGE_ALONG, STORAGE_Y);
    }

    /**
     * Playtest 2026-10-02-1: every staging room carries the run storage ender
     * chest, so it is there for every member on every interval without anyone
     * placing it. Set into the wall ring, facing into the room: the ring is
     * shell, so the chest cannot be broken or blown up, and it takes no floor.
     */
    static void placeRunStorage(ServerLevel level, BlockPos o, DoorMask.Direction selectorWall) {
        net.minecraft.core.Direction facing = Instances.mcDirection(storageWall(selectorWall)).getOpposite();
        RoomBuilder.set(level, runStoragePos(o, selectorWall), RunStorage.stationState(facing));
    }

    /**
     * M19: stamps the room's physical door-selection furniture, relative to
     * {@code wall}, the wall the selector doors stand on: one copper bulb set
     * into the wall at Y=3 above each of the three selector doors, the commit
     * lever at Y=2 beside the third door with its label sign at Y=3 above it,
     * the black concrete door screen set into the selector wall above all of
     * that, and on the wall to the left ({@link RoomGeometry#leftOf}) the
     * respawn-anchor engine block with its own screen above it. The positions
     * must stay in lockstep with {@link RoomProtection#isFurniture} and
     * {@link #clearFurniture}; {@code RoomFurnitureTest} pins them.
     *
     * <p>{@code selectorDoorsStanding} is what decides whether the bulbs go in
     * at all. They share the wall course the punched doorway uses for its
     * lintel, so on a room whose door has already been chosen they would stamp
     * a copper bulb into the middle of the finished doorway. There is nothing
     * for them to indicate on such a room either: the choosing is over.
     *
     * <p>Called after {@link #placeSelectorDoors} and
     * {@link #placeWallLodestone} by every path that arms a lobby: stampLobby,
     * createVisitInstance, and completeDungeon's room relocation.
     */
    static void placeFurniture(ServerLevel level, BlockPos o, DoorMask.Direction wall,
                               boolean selectorDoorsStanding) {
        if (selectorDoorsStanding) {
            for (int along : SELECTOR_DOORS) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), BULB);
            }
        }
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), 2), leverState(wall));
        placeLeverSign(level, o, wall);
        for (int y = 4; y <= 5; y++) {
            for (int along = DOOR_SCREEN_ALONG_MIN; along <= DOOR_SCREEN_ALONG_MAX; along++) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, y), SCREEN_BLOCK);
            }
        }
        placeHistoryPanel(level, o, RoomGeometry.leftOf(wall));
    }

    /**
     * Whether {@code pos} is a block of the floor history board's panel in the
     * staging room at {@code o} whose selector doors face {@code selectorWall}.
     * Right-clicking it opens the dungeon map (dungeon structure W2).
     */
    static boolean isHistoryPanel(BlockPos o, DoorMask.Direction selectorWall, BlockPos pos) {
        DoorMask.Direction historyWall = RoomGeometry.leftOf(selectorWall);
        for (int y = HISTORY_Y_MIN; y <= HISTORY_Y_MAX; y++) {
            for (int along = HISTORY_ALONG_MIN; along <= HISTORY_ALONG_MAX; along++) {
                if (wallRingPos(o, historyWall, along, y).equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The floor history board's panel; its rows cover every block the old engine bay used. */
    private static void placeHistoryPanel(ServerLevel level, BlockPos o, DoorMask.Direction historyWall) {
        for (int y = HISTORY_Y_MIN; y <= HISTORY_Y_MAX; y++) {
            for (int along = HISTORY_ALONG_MIN; along <= HISTORY_ALONG_MAX; along++) {
                RoomBuilder.set(level, wallRingPos(o, historyWall, along, y), SCREEN_BLOCK);
            }
        }
    }

    /**
     * Removes every M19 furniture block, restoring the room to its captured
     * shape: the lever and its sign were interior air, the bulbs, the door
     * screen and the floor history panel sat in the wall ring, so
     * each clears back to the block it displaced. Capture hygiene (trap 17 in
     * {@code DISCOVERIES.md}): none of it may bake into the owner's blob.
     *
     * <p>Unconditional where {@link #placeFurniture} is not: writing plain wall
     * over the bulb course is right whether a bulb or a doorway lintel is
     * standing there, since the lintel is that same stone brick.
     */
    static void clearFurniture(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        clearBulbs(level, o, wall);
        clearHomeControl(level, o, wall);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), 2), RoomBuilder.AIR);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), SIGN_Y), RoomBuilder.AIR);
        for (int y = 4; y <= 5; y++) {
            for (int along = DOOR_SCREEN_ALONG_MIN; along <= DOOR_SCREEN_ALONG_MAX; along++) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, y), RoomBuilder.WALL);
            }
        }
        DoorMask.Direction historyWall = RoomGeometry.leftOf(wall);
        for (int y = HISTORY_Y_MIN; y <= HISTORY_Y_MAX; y++) {
            for (int along = HISTORY_ALONG_MIN; along <= HISTORY_ALONG_MAX; along++) {
                RoomBuilder.set(level, wallRingPos(o, historyWall, along, y), RoomBuilder.WALL);
            }
        }
    }

    /**
     * Stamps the go-home control (see {@link #HOME_LEVER_ALONG}) in a cleared
     * floor's staging room. {@code goodTime} lights the bulb: the interval
     * has run its usual length and banking now is the sensible default.
     */
    static void placeHomeControl(ServerLevel level, BlockPos o, DoorMask.Direction wall, boolean goodTime) {
        for (int along = HOME_SCREEN_ALONG_MIN; along <= HOME_SCREEN_ALONG_MAX; along++) {
            for (int y = HOME_SCREEN_Y_MIN; y <= HOME_SCREEN_Y_MAX; y++) {
                RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, along), y), SCREEN_BLOCK);
            }
        }
        setHomeBulb(level, o, wall, goodTime);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), 2), leverState(wall));
        placeSign(level, doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), SIGN_Y), wall, goodTime ? LEAVE_SIGN_WORD : HOME_SIGN_WORD);
    }

    /** Lights or darkens the bulb over the go-home screen. */
    static void setHomeBulb(ServerLevel level, BlockPos o, DoorMask.Direction wall, boolean lit) {
        RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, HOME_BULB_ALONG), HOME_BULB_Y),
                lit ? BULB_LIT : BULB);
    }

    /** PD-70: see {@link RoomGeometry#viewerAlong}; the levers and go-home control go through it. */
    private static int viewerAlong(DoorMask.Direction wall, int along) {
        return RoomGeometry.viewerAlong(wall, along);
    }

    /**
     * Takes the go-home control back out: the lever and sign to air, the
     * screen and bulb to the room's current shell wall. Run when a door is
     * committed (the choosing is over), when the party goes home, and with
     * the rest of the furniture when a staging room is left behind.
     */
    static void clearHomeControl(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        BlockState wallBlock = RoomBuilder.shellWallAt(level, o);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), SIGN_Y), RoomBuilder.AIR);
        RoomBuilder.set(level, doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), 2), RoomBuilder.AIR);
        for (int along = HOME_SCREEN_ALONG_MIN; along <= HOME_SCREEN_ALONG_MAX; along++) {
            for (int y = HOME_SCREEN_Y_MIN; y <= HOME_SCREEN_Y_MAX; y++) {
                RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, along), y), wallBlock);
            }
        }
        RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, HOME_BULB_ALONG), HOME_BULB_Y), wallBlock);
    }

    /** The go-home lever's position, for click detection ({@code Instances.isHomeLever}). */
    static BlockPos homeLeverPos(BlockPos o, DoorMask.Direction wall) {
        return doorPlanePos(o, wall, viewerAlong(wall, HOME_LEVER_ALONG), 2);
    }

    /**
     * Lights or darkens one bulb in the wall above a selector door.
     * {@code along} is a position on the wall (7, 8 or 9), not a door step;
     * see {@link #bulbAlongForStep}.
     */
    static void setBulb(ServerLevel level, BlockPos o, DoorMask.Direction wall, int along, boolean lit) {
        RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), lit ? BULB_LIT : BULB);
    }

    /**
     * Hands the whole bulb course back to plain wall, for the moment a door is
     * chosen and the doorway is punched. The choosing is over, so the
     * indicators come out rather than going dark: two of the three positions
     * are about to become the new doorway's lintel, and the third is the wall
     * beside it. The wall block is the room's current shell, so a swapped
     * shell (M24) does not keep a stone-brick patch where the bulbs were.
     */
    static void clearBulbs(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        BlockState wallBlock = RoomBuilder.shellWallAt(level, o);
        for (int along : SELECTOR_DOORS) {
            RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), wallBlock);
        }
        // The Astrolabe Room's bulbs stand on the same course (design pass 2026-10-09, Q1).
        for (int rel : HallLayout.DOOR_SPACES) {
            RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, rel), BULB_Y), wallBlock);
        }
        for (int rel : HallLayout.SPECIAL_SPACES) {
            RoomBuilder.set(level, wallRingPos(o, wall, viewerAlong(wall, rel), BULB_Y), wallBlock);
        }
    }

    /**
     * The commit lever's label: a waxed wall sign one course above the lever,
     * facing into the room. Waxed so a right-click cannot open the text editor
     * on it, which is the only interaction {@code RoomProtection} does not
     * already refuse (it guards breaking and placing, not editing).
     *
     * <p>The word is written to line {@link #LEVER_SIGN_LINE} of the front
     * text and nothing else, which is what centers it: vanilla renders every
     * sign line centered horizontally, so the only choice left is which of the
     * four rows it sits on.
     */
    private static void placeLeverSign(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        placeSign(level, doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), SIGN_Y), wall, LEVER_SIGN_WORD);
    }

    /** A waxed, glowing one-word wall sign at {@code pos}, facing into the room from {@code wall}. */
    private static void placeSign(ServerLevel level, BlockPos pos, DoorMask.Direction wall, String word) {
        RoomBuilder.set(level, pos, signState(wall));
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            PocketDungeonsMod.LOG.warn("The lever sign at {} did not come with a block entity", pos);
            return;
        }
        SignText text = new SignText()
                .setMessage(LEVER_SIGN_LINE, Component.literal(word))
                .setColor(DyeColor.WHITE)
                .setHasGlowingText(true);
        sign.setText(text, true);
        sign.setWaxed(true);
        sign.setChanged();
    }

    /** The commit lever's position, for click detection ({@code Instances.isCommitLever}). */
    static BlockPos leverPos(BlockPos o, DoorMask.Direction wall) {
        return doorPlanePos(o, wall, viewerAlong(wall, LEVER_ALONG), 2);
    }

    /** Position one block inside the room from {@code wall}, in the door row. */
    private static BlockPos doorPlanePos(BlockPos o, DoorMask.Direction wall, int along, int y) {
        return switch (wall) {
            case NORTH -> o.offset(along, y, 1);
            case SOUTH -> o.offset(along, y, RoomGeometry.CELL - 2);
            case EAST -> o.offset(RoomGeometry.CELL - 2, y, along);
            case WEST -> o.offset(1, y, along);
        };
    }

    /** Position in the wall ring itself, on {@code wall}. */
    private static BlockPos wallRingPos(BlockPos o, DoorMask.Direction wall, int along, int y) {
        return switch (wall) {
            case NORTH -> o.offset(along, y, 0);
            case SOUTH -> o.offset(along, y, RoomGeometry.CELL - 1);
            case EAST -> o.offset(RoomGeometry.CELL - 1, y, along);
            case WEST -> o.offset(0, y, along);
        };
    }

    /**
     * A wall-attached lever whose base sits against {@code wall}: the FACING
     * property is the direction the lever's handle points, away from the wall
     * it is attached to and into the room. Verified in
     * {@code FaceAttachedHorizontalDirectionalBlock}'s placement: it stores
     * {@code direction.getOpposite()} of the look ray that hit the block, and
     * that ray runs from the room toward the wall, so FACING comes back out
     * again. The selector wall is behind the lever, so the handle faces the
     * opposite direction.
     */
    private static BlockState leverState(DoorMask.Direction wall) {
        return LEVER_OFF.setValue(LeverBlock.FACING, CellGeometry.facingIntoRoom(wall));
    }

    /**
     * The lever's sign, hung on {@code wall} and facing into the room. A wall
     * sign's FACING is the direction its face points, away from the block it
     * is attached to, so it reads the same way {@link #leverState} does.
     */
    private static BlockState signState(DoorMask.Direction wall) {
        return LEVER_SIGN.setValue(WallSignBlock.FACING, CellGeometry.facingIntoRoom(wall));
    }

    static BlockPos[] concat(BlockPos[] base, BlockPos... extra) {
        BlockPos[] out = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    // ---- test hooks ---------------------------------------------------------

    /** The spec of the room named {@code name} (library or anomaly), or {@code null}. */
    static RoomSpec specNamed(String name) {
        for (RoomSpec spec : specs()) {
            if (spec.name.equals(name)) {
                return spec;
            }
        }
        for (RoomSpec spec : anomalySpecs()) {
            if (spec.name.equals(name)) {
                return spec;
            }
        }
        return null;
    }

    /** Every library room name, in generation order. */
    static List<String> specNames() {
        List<String> names = new ArrayList<>();
        for (RoomSpec spec : specs()) {
            names.add(spec.name);
        }
        return names;
    }

    /**
     * Builds {@code spec} at {@code o} exactly as the generator does before capture (shell, door
     * jigsaws, decor, chests, spawn markers, spawner, exit pad), without queueing a capture. For
     * checks that walk the finished room.
     */
    static void buildForCheck(ServerLevel level, BlockPos o, RoomSpec spec) {
        if (spec.shellPalette != null) {
            buildCellWithPalette(level, o, spec.doors, spec.shellPalette, spec.spanY);
        } else {
            buildCell(level, o, spec.doors, spec.spanY);
        }
        if (spec.decor != null) {
            spec.decor.accept(level, o);
        }
        for (BlockPos chest : spec.chests) {
            placeChest(level, o.offset(chest));
        }
        for (BlockPos spawn : spec.spawns) {
            placeSpawnPoint(level, o.offset(spawn));
        }
        if (spec.spawner != null) {
            placeSpawner(level, o.offset(spec.spawner));
        }
        if (spec.exitPad) {
            placeExitPad(level, o);
        }
    }

    // ---- building -----------------------------------------------------------

    private static void buildAndQueue(ServerLevel level, Path outDir, RoomSpec spec, int zOffset) {
        BlockPos o = new BlockPos(SCRATCH_X, BASE_Y, SCRATCH_Z + zOffset);
        forceChunks(level, o, true);

        if (spec.shellPalette != null) {
            buildCellWithPalette(level, o, spec.doors, spec.shellPalette, spec.spanY);
        } else {
            buildCell(level, o, spec.doors, spec.spanY);
        }
        if (spec.decor != null) {
            spec.decor.accept(level, o);
        }
        for (BlockPos chest : spec.chests) {
            placeChest(level, o.offset(chest));
        }
        for (BlockPos spawn : spec.spawns) {
            placeSpawnPoint(level, o.offset(spawn));
        }
        if (spec.spawner != null) {
            placeSpawner(level, o.offset(spec.spawner));
        }
        if (spec.exitPad) {
            placeExitPad(level, o);
        }

        int offset = RoomGeometry.storyOffset(spec.spanY);
        BlockPos captureOrigin = offset == 0 ? o : o.below(offset);
        Vec3i size = templateSize(spec.spanY);
        Path outFile = outDir.resolve(spec.name + ".nbt");
        queue.add(new Task(() -> {
            try {
                captureAndSave(level, captureOrigin, size, outFile);
            } catch (Exception e) {
                PocketDungeonsMod.LOG.error("Failed to save template {}", spec.name, e);
            } finally {
                clear(level, captureOrigin, size);
                forceChunks(level, o, false);
            }
        }, 1));
    }

    /**
     * The same blank shell {@link RoomBuilder#buildCell} stamps for a live room
     * (M9 C2's shared {@link RoomBuilder#buildShell}), finished differently: a
     * template's door slots get a jigsaw block instead of open air, since a
     * template has no neighbour on the other side to connect to yet, only a
     * marker for the generator to erase and reconnect at stamp time
     * ({@link JigsawFallback}).
     */
    private static void buildCell(ServerLevel level, BlockPos o, Set<Direction> doors, int spanY) {
        RoomBuilder.buildShell(level, o, RoomBuilder.FLOOR);
        buildLowerStories(level, o, RoomBuilder.STONE_BRICK, spanY);
        placeDoorJigsaws(level, o, doors);
    }

    /**
     * M35: {@link #buildCell}'s shell built from {@code palette} instead of the
     * fixed stone-brick shell, via {@link RoomBuilder#stampShell}. Door jigsaws
     * are overlaid identically afterward -- the palette only changes what the
     * jigsaw sits on top of, never where a door is.
     */
    private static void buildCellWithPalette(ServerLevel level, BlockPos o, Set<Direction> doors,
                                             RoomBuilder.ShellPalette palette, int spanY) {
        RoomBuilder.stampShell(level, o, palette);
        buildLowerStories(level, o, palette, spanY);
        placeDoorJigsaws(level, o, doors);
    }

    /**
     * M61: stamps a plain shell for each story beneath the cell. A lower story
     * is private interior with no doorways (spec 13.3), so it gets a full
     * {@link RoomBuilder#stampShell} and no jigsaws.
     *
     * <p>Each lower story also gets solid over-ceiling filler from above its
     * ceiling to below the story above's floor. With STORY_HEIGHT = CEILING_Y +
     * 2, the floor-to-floor pitch leaves a 2-block gap between one story's
     * ceiling and the story above's floor; filling it encloses the lower story,
     * gives a ladder a solid block to attach to at the top of its shaft, and
     * replaces the sub-floor bedrock the envelope would have placed for a
     * single-story room. The room's decor carves the shaft holes through it.
     */
    private static void buildLowerStories(ServerLevel level, BlockPos o,
                                          RoomBuilder.ShellPalette palette, int spanY) {
        for (int story = 1; story < spanY; story++) {
            BlockPos storyOrigin = o.below(story * STORY_HEIGHT);
            RoomBuilder.stampShell(level, storyOrigin, palette);
            // Fill from above this story's ceiling to below the story above's
            // floor. For story 1 under the cell: local y = -2 and y = -1.
            int fillStart = storyOrigin.getY() + RoomGeometry.CEILING_Y + 1;
            int fillEnd = o.getY() - ((story - 1) * STORY_HEIGHT) - 1;
            for (int y = fillStart; y <= fillEnd; y++) {
                for (int x = 0; x < CELL; x++) {
                    for (int z = 0; z < CELL; z++) {
                        RoomBuilder.set(level, new BlockPos(o.getX() + x, y, o.getZ() + z),
                                palette.wall());
                    }
                }
            }
        }
    }

    private static void placeDoorJigsaws(ServerLevel level, BlockPos o, Set<Direction> doors) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                boolean edge = x == 0 || x == CELL - 1 || z == 0 || z == CELL - 1;
                if (!edge) {
                    continue;
                }
                Direction doorDir = RoomGeometry.wallDirection(x, z);
                if (doorDir == null || !doors.contains(doorDir)) {
                    continue;
                }
                for (int y = 1; y <= WALL_HEIGHT; y++) {
                    if (isDoorSlot(x, y, z, doorDir)) {
                        placeJigsaw(level, o.offset(x, y, z), DOOR, orientationFor(doorDir));
                    }
                }
            }
        }
    }

    /** A vertical run of one block, inclusive of both y bounds. */
    static void column(ServerLevel level, BlockPos o, int x, int yFrom, int yTo, int z,
                               BlockState state) {
        for (int y = yFrom; y <= yTo; y++) {
            RoomBuilder.set(level, o.offset(x, y, z), state);
        }
    }

    /** Floor inlay ringing the centre of the cell. Purely cosmetic, never solid
     *  above the floor, so it cannot obstruct any route. */
    static void floorRing(ServerLevel level, BlockPos o, BlockState state) {
        for (int i = 6; i <= 9; i++) {
            RoomBuilder.set(level, o.offset(i, 0, 6), state);
            RoomBuilder.set(level, o.offset(i, 0, 9), state);
            RoomBuilder.set(level, o.offset(6, 0, i), state);
            RoomBuilder.set(level, o.offset(9, 0, i), state);
        }
    }

    private static boolean isDoorSlot(int x, int y, int z, Direction door) {
        if (y < 1 || y > RoomGeometry.DOOR_HEIGHT) return false;
        int i = switch (door) {
            case NORTH, SOUTH -> x;
            case WEST, EAST -> z;
            default -> -1;
        };
        return i >= RoomGeometry.DOOR_MIN && i <= RoomGeometry.DOOR_MAX;
    }

    private static FrontAndTop orientationFor(Direction door) {
        return switch (door) {
            case NORTH -> FrontAndTop.NORTH_UP;
            case SOUTH -> FrontAndTop.SOUTH_UP;
            case WEST -> FrontAndTop.WEST_UP;
            case EAST -> FrontAndTop.EAST_UP;
            default -> throw new IllegalArgumentException("door must be horizontal: " + door);
        };
    }

    private static void placeJigsaw(ServerLevel level, BlockPos pos, Identifier name,
                                    FrontAndTop orientation) {
        RoomBuilder.set(level, pos, Blocks.JIGSAW.defaultBlockState()
                .setValue(JigsawBlock.ORIENTATION, orientation));
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof JigsawBlockEntity jigsaw) {
            jigsaw.setName(name);
            jigsaw.setFinalState("minecraft:air");
        } else {
            PocketDungeonsMod.LOG.warn("Jigsaw at {} has no block entity", pos);
        }
    }

    /**
     * A mob spawn point, authored as a jigsaw rather than the spec's marker
     * entity. {@code StructureTemplate.getJigsaws} hands the stamper positions
     * already transformed for the placement rotation, {@code RoomManifest} already
     * ignores every jigsaw name but {@code door} when deriving masks, and
     * {@link JigsawFallback} already erases anything in this namespace after
     * placement -- so the whole mechanism is machinery that is proven and shipped,
     * with no new API surface to verify.
     *
     * <p>Its orientation is arbitrary: nothing reads it. {@code UP_NORTH} just
     * makes it obvious in the authoring jig that it is not a door.
     */
    private static void placeSpawnPoint(ServerLevel level, BlockPos pos) {
        placeJigsaw(level, pos, SPAWN, FrontAndTop.UP_NORTH);
    }

    private static void placeChest(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH));
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RandomizableContainer container) {
            // A placeholder: the stamper retargets every chest to the run's tier
            // table. Authoring it pointed somewhere real keeps a hand-placed
            // template honest if it is ever used outside the stamper.
            container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, TIER_1_LOOT));
        } else {
            PocketDungeonsMod.LOG.warn("Template chest at {} has no block entity", pos);
        }
    }

    /**
     * A real vanilla mob spawner, tuned to be a fight rather than a farm.
     *
     * <p>{@code MaxNearbyEntities: 6} is the anti-farm lever -- the spawner stops
     * dead once six of its mobs are alive nearby, so an AFK player in a
     * force-loaded instance accumulates nothing. The room's metadata caps it at
     * one per dungeon and keeps it out of the opening cells.
     *
     * <p>The tuning goes in through {@code BaseSpawner.load}, which in 26.2 takes
     * a {@link ValueInput} rather than a raw {@code CompoundTag}. {@code SpawnData}
     * is deliberately <em>not</em> in the tag: {@code load} only overwrites it when
     * the key is present, so leaving it out preserves what {@code setEntityId}
     * just set.
     */
    private static void placeSpawner(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.SPAWNER.defaultBlockState());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SpawnerBlockEntity spawner)) {
            PocketDungeonsMod.LOG.warn("Template spawner at {} has no block entity", pos);
            return;
        }

        spawner.setEntityId(EntityTypes.ZOMBIE, level.getRandom());

        CompoundTag tuning = new CompoundTag();
        tuning.putInt("SpawnCount", 2);
        tuning.putInt("SpawnRange", 4);
        tuning.putInt("MinSpawnDelay", 200);
        tuning.putInt("MaxSpawnDelay", 400);
        tuning.putInt("MaxNearbyEntities", 6);
        tuning.putInt("RequiredPlayerRange", 12);

        ValueInput input = TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), tuning);
        spawner.getSpawner().load(level, pos, input);
        spawner.setChanged();
    }

    /**
     * The exit pad: a 2x2 lodestone square at local (7..8, 0, 7..8).
     *
     * <p>Two by two, not one block, because {@code (8,·,8)} is <em>not</em> a fixed
     * point of the rotation transform on an even-sized template -- a single
     * lodestone authored at the cell centre lands at {@code (7,·,8)} once the room
     * is rotated 90 degrees. The 2x2 square is the one interior feature that maps
     * onto itself at every rotation.
     */
    private static void placeExitPad(ServerLevel level, BlockPos o) {
        BlockState lodestone = Blocks.LODESTONE.defaultBlockState();
        BlockState ring = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        for (int x = RoomGeometry.DOOR_MIN; x <= RoomGeometry.DOOR_MAX; x++) {
            for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
                RoomBuilder.set(level, o.offset(x, 0, z), lodestone);
            }
        }
        for (int i = RoomGeometry.DOOR_MIN - 1; i <= RoomGeometry.DOOR_MAX + 1; i++) {
            RoomBuilder.set(level, o.offset(i, 0, RoomGeometry.DOOR_MIN - 1), ring);
            RoomBuilder.set(level, o.offset(i, 0, RoomGeometry.DOOR_MAX + 1), ring);
            RoomBuilder.set(level, o.offset(RoomGeometry.DOOR_MIN - 1, 0, i), ring);
            RoomBuilder.set(level, o.offset(RoomGeometry.DOOR_MAX + 1, 0, i), ring);
        }
    }

    private static void captureAndSave(ServerLevel level, BlockPos origin, Vec3i size, Path outFile)
            throws IOException {
        StructureTemplate template = new StructureTemplate();
        Lemon.withoutLemon(level, new net.minecraft.world.phys.AABB(origin.getX(), origin.getY(), origin.getZ(),
                        origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ()),
                () -> template.fillFromWorld(level, origin, size, true, List.of()));
        CompoundTag nbt = template.save(new CompoundTag());
        NbtIo.writeCompressed(nbt, outFile);
        PocketDungeonsMod.LOG.info("Saved room template to {}", outFile);
    }

    /**
     * Captures the cell at {@code cellOrigin} into a hand-authored room
     * template, for {@code /dungeon admin saveroom}: the same resources
     * directory the generator writes to, named
     * {@code <name>_<author>_<timestamp>.nbt}, with the author and save time
     * riding on the blob as {@code pd_author} and {@code pd_saved_at} for the
     * code generator to read back. Both are unknown to
     * {@code StructureTemplate.load}, so the file stays loadable by the room
     * manifest like any other template.
     *
     * @return the file written, or {@code null} if the directory could not be
     *         created or the write failed
     */
    static Path captureRoomToFile(ServerLevel level, BlockPos cellOrigin, String name,
                                  String authorName, UUID authorUuid) {
        Path outDir = templateOutDir();
        if (outDir == null) {
            return null;
        }
        Path outFile = outDir.resolve(sanitizeName(name) + "_" + sanitizeName(authorName)
                + "_" + System.currentTimeMillis() + ".nbt");
        try {
            StructureTemplate template = new StructureTemplate();
            Lemon.withoutLemon(level, new net.minecraft.world.phys.AABB(cellOrigin.getX(), cellOrigin.getY(),
                            cellOrigin.getZ(), cellOrigin.getX() + TEMPLATE_SIZE.getX(),
                            cellOrigin.getY() + TEMPLATE_SIZE.getY(), cellOrigin.getZ() + TEMPLATE_SIZE.getZ()),
                    () -> template.fillFromWorld(level, cellOrigin, TEMPLATE_SIZE, true, List.of()));
            CompoundTag nbt = template.save(new CompoundTag());
            nbt.putString("pd_author", authorUuid.toString());
            nbt.putLong("pd_saved_at", System.currentTimeMillis());
            NbtIo.writeCompressed(nbt, outFile);
            PocketDungeonsMod.LOG.info("Saved room template to {}", outFile);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not save room template '{}'", name, e);
            return null;
        }
        return outFile;
    }

    /** {@code [a-z0-9_-]} only, so a command argument can never escape the rooms directory. */
    private static String sanitizeName(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return cleaned.isEmpty() ? "room" : cleaned;
    }

    private static void clear(ServerLevel level, BlockPos origin, Vec3i size) {
        for (int x = 0; x < size.getX(); x++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int y = 0; y < size.getY(); y++) {
                    RoomBuilder.set(level, origin.offset(x, y, z), RoomBuilder.AIR);
                }
            }
        }
    }

    private static void forceChunks(ServerLevel level, BlockPos o, boolean forced) {
        int minChunkX = o.getX() >> 4;
        int minChunkZ = o.getZ() >> 4;
        int maxChunkX = (o.getX() + CELL - 1) >> 4;
        int maxChunkZ = (o.getZ() + CELL - 1) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }
}
