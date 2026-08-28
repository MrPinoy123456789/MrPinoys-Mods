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
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
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
import java.util.function.BiConsumer;

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

    private static final int CELL = RoomGeometry.CELL;
    private static final int WALL_HEIGHT = RoomGeometry.WALL_HEIGHT;
    private static final int CEILING_Y = RoomGeometry.CEILING_Y;
    private static final Vec3i TEMPLATE_SIZE = new Vec3i(CELL, CEILING_Y + 1, CELL);

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
    private static final Set<Direction> HORIZONTALS = EnumSet.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    /** The four common spawn points. This set is closed under rotation, which
     *  makes the {@code stamptest} cross-check easy to read. */
    private static final BlockPos[] QUAD_SPAWNS = {
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
        Path outDir = templateOutDir();
        if (outDir == null) {
            return;
        }

        List<RoomSpec> specs = specs();
        for (int i = 0; i < specs.size(); i++) {
            buildAndQueue(level, outDir, specs.get(i), i * SCRATCH_PITCH);
        }

        PocketDungeonsMod.LOG.info("Queued {} pocketdungeons room templates for capture", specs.size());
    }

    /**
     * The resources directory room templates are written to, created on
     * demand. Dev-environment only: the game dir's parent is the mod project,
     * so this is the source tree itself.
     *
     * @return the directory, or {@code null} if it could not be created
     */
    static Path templateOutDir() {
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

    // ---- the library --------------------------------------------------------

    private static List<RoomSpec> specs() {
        List<RoomSpec> specs = new ArrayList<>();

        // --- end caps: single-door, single-role. One template each covers all
        //     four single-door masks by rotation, which is only true because the
        //     graph generator holds the entrance and terminal cells to one door.

        // No mob: the entrance is where a party arrives and regroups, and where
        // the void guard bounces a falling player back to. It stays safe.
        specs.add(new RoomSpec("entrance_hall", EnumSet.of(Direction.SOUTH))
                .decor((level, o) -> {
                    placeSelectorDoors(level, o, DoorMask.Direction.SOUTH);
                    placeWallLodestone(level, o);
                }));

        specs.add(new RoomSpec("exit_hall", EnumSet.of(Direction.WEST)).exitPad());

        // --- coverage floor: every mask family, all three content roles.

        specs.add(new RoomSpec("hall_dead_end", EnumSet.of(Direction.NORTH))
                .chests(new BlockPos(2, 1, 2))
                .spawns(new BlockPos(4, 1, 11), new BlockPos(11, 1, 11))
                .decor((level, o) -> {
                    // Decorative back wall, opposite the only door.
                    for (int x = 1; x <= CELL - 2; x++) {
                        for (int y = 1; y <= WALL_HEIGHT; y++) {
                            RoomBuilder.set(level, o.offset(x, y, CELL - 1),
                                    Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                        }
                    }
                }));

        specs.add(new RoomSpec("hall_straight", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    // Half-height pillars either side of the through-route: they
                    // break the sightline without closing either doorway, which is
                    // two blocks wide.
                    column(level, o, 4, 1, 3, 7, Blocks.STONE_BRICK_WALL.defaultBlockState());
                    column(level, o, 11, 1, 3, 8, Blocks.STONE_BRICK_WALL.defaultBlockState());
                }));

        specs.add(new RoomSpec("hall_corner", EnumSet.of(Direction.NORTH, Direction.EAST))
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> floorRing(level, o,
                        Blocks.CHISELED_STONE_BRICKS.defaultBlockState())));

        specs.add(new RoomSpec("hall_tee",
                EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .chests(new BlockPos(2, 1, 2))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    // Shelf along the doorless west wall, split around the lane so
                    // the room stays correct if a west door is ever authored in.
                    BlockState slab = Blocks.STONE_BRICK_SLAB.defaultBlockState();
                    for (int z = 1; z <= CELL - 2; z++) {
                        if (z >= RoomGeometry.DOOR_MIN - 1 && z <= RoomGeometry.DOOR_MAX + 1) {
                            continue;
                        }
                        RoomBuilder.set(level, o.offset(1, 2, z), slab);
                    }
                }));

        specs.add(new RoomSpec("hall_cross", HORIZONTALS)
                .chests(new BlockPos(2, 1, 2))
                .spawns(concat(QUAD_SPAWNS, new BlockPos(2, 1, 5), new BlockPos(13, 1, 10)))
                .decor((level, o) -> {
                    column(level, o, 5, 1, WALL_HEIGHT, 5, RoomBuilder.WALL);
                    column(level, o, 10, 1, WALL_HEIGHT, 5, RoomBuilder.WALL);
                    column(level, o, 5, 1, WALL_HEIGHT, 10, RoomBuilder.WALL);
                    column(level, o, 10, 1, WALL_HEIGHT, 10, RoomBuilder.WALL);
                }));

        // --- flavour layer: same shapes, higher weight, themed.

        specs.add(new RoomSpec("encounter_zombie", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(QUAD_SPAWNS));

        // The chest moves off (8,1,2): that sits squarely in the north doorway
        // lane, harmless only for as long as the room is never rotated.
        specs.add(new RoomSpec("loot_vault", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 2)));

        specs.add(new RoomSpec("crypt_corner", EnumSet.of(Direction.NORTH, Direction.EAST))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    BlockState lid = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                    for (int x = 3; x <= 5; x++) {
                        for (int z = 11; z <= 12; z++) {
                            RoomBuilder.set(level, o.offset(x, 1, z), lid);
                        }
                    }
                    BlockState web = Blocks.COBWEB.defaultBlockState();
                    RoomBuilder.set(level, o.offset(1, 1, 13), web);
                    RoomBuilder.set(level, o.offset(1, 2, 14), web);
                    RoomBuilder.set(level, o.offset(2, 1, 14), web);
                    RoomBuilder.set(level, o.offset(14, 1, 13), web);
                }));

        specs.add(new RoomSpec("treasure_alcove", EnumSet.of(Direction.NORTH))
                .chests(new BlockPos(2, 1, 2), new BlockPos(13, 1, 2))
                .decor((level, o) -> {
                    RoomBuilder.set(level, o.offset(8, 0, 12),
                            Blocks.GOLD_BLOCK.defaultBlockState());
                    RoomBuilder.set(level, o.offset(7, 0, 12),
                            Blocks.GOLD_BLOCK.defaultBlockState());
                }));

        specs.add(new RoomSpec("pillar_cross", HORIZONTALS)
                .spawns(concat(QUAD_SPAWNS, new BlockPos(2, 1, 5), new BlockPos(13, 1, 10)))
                .decor((level, o) -> {
                    BlockState deepslate = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                    column(level, o, 5, 1, WALL_HEIGHT, 5, deepslate);
                    column(level, o, 10, 1, WALL_HEIGHT, 5, deepslate);
                    column(level, o, 5, 1, WALL_HEIGHT, 10, deepslate);
                    column(level, o, 10, 1, WALL_HEIGHT, 10, deepslate);
                    RoomBuilder.set(level, o.offset(5, 0, 5), RoomBuilder.LAMP);
                    RoomBuilder.set(level, o.offset(10, 0, 5), RoomBuilder.LAMP);
                    RoomBuilder.set(level, o.offset(5, 0, 10), RoomBuilder.LAMP);
                    RoomBuilder.set(level, o.offset(10, 0, 10), RoomBuilder.LAMP);
                }));

        // Named for the look, not for water: a flooded room would need source
        // blocks that spread out through the doorways into the neighbouring cell,
        // and a contained cistern buys nothing a moss floor does not.
        specs.add(new RoomSpec("mossy_tee",
                EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .spawns(QUAD_SPAWNS)
                .decor((level, o) -> {
                    BlockState mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                    for (int x = 4; x <= 11; x++) {
                        for (int z = 4; z <= 11; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), mossy);
                        }
                    }
                    for (int y = 1; y <= WALL_HEIGHT; y++) {
                        RoomBuilder.set(level, o.offset(1, y, 1), mossy);
                        RoomBuilder.set(level, o.offset(14, y, 14), mossy);
                    }
                }));

        specs.add(new RoomSpec("spawner_den", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 12))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    // Classic vanilla-dungeon plinth. Dead centre blocks only one
                    // of the two doorway columns, so the room is still walkable.
                    RoomBuilder.set(level, o.offset(8, 0, 8),
                            Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                    for (int x = 6; x <= 10; x++) {
                        for (int z = 6; z <= 10; z++) {
                            if (x == 8 && z == 8) {
                                continue;
                            }
                            RoomBuilder.set(level, o.offset(x, 0, z),
                                    Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                        }
                    }
                }));

        // The authoring reference from M1: an empty sealed box with all four
        // doors and nothing else. Never selected, no metadata file.
        specs.add(new RoomSpec("jig_room", HORIZONTALS));

        // --- U8: the reward room and the selector room. Neither has a door --
        //     both are reached by teleport, so neither has a mask, and neither
        //     gets a dungeon_room/*.json: loading either through the manifest
        //     would put it in the coverage grid and the planner's plan graph for
        //     no benefit (U8 Stage 2/3). Stamped directly by Identifier through
        //     TemplateStamper.place, the same way StaticLayout's fallback rooms
        //     already are.

        // Three chests, scaled by how much of the clock is left; a lodestone
        // pad -- the same rotation-invariant 2x2 square every exit uses -- to
        // leave. Reached only after the terminal cell's own pad is stamped.
        specs.add(new RoomSpec("reward_hall", Set.of())
                .chests(new BlockPos(4, 1, 4), new BlockPos(8, 1, 4), new BlockPos(12, 1, 4))
                .exitPad());

        // Three doors -- the visual language for an affix -- and a lodestone
        // pad of its own so leaving without choosing costs nothing. The pad
        // sits well south of the door row (T12 gives no fixed coordinate for
        // it, only for the doors), clear of both.
        specs.add(new RoomSpec("selector_room", Set.of())
                .decor((level, o) -> {
                    placeSelectorDoors(level, o, DoorMask.Direction.SOUTH);
                    placeWallLodestone(level, o);
                }));

        return specs;
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
    /** The engine terminal: charges=0 by default; the engine handler raises it. */
    private static final BlockState ENGINE_BLOCK = Blocks.RESPAWN_ANCHOR.defaultBlockState();
    /**
     * The engine screen's bezel. Polished blackstone stairs above and below,
     * the lower course flipped so the two mirror each other and their solid
     * halves hug the screen row, with a crying obsidian block capping each end.
     * The screen used to be two bare rows of black concrete in a stone wall,
     * which read as a hole rather than as a machine.
     */
    private static final BlockState FRAME = Blocks.POLISHED_BLACKSTONE_STAIRS.defaultBlockState();
    private static final BlockState FRAME_END = Blocks.CRYING_OBSIDIAN.defaultBlockState();
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
     * The engine bay on the wall to the left of the selector wall, bottom up:
     * the respawn anchor at Y=2, the flipped lower bezel course at Y=3, the
     * screen row at Y=4 with a crying obsidian block at each end, and the upper
     * bezel course at Y=5. Y=6 is the ceiling, so Y=5 is as high as the bay can
     * reach and the screen is one row rather than the two it used to be; the
     * text was never taller than a block at this scale anyway.
     */
    private static final int ENGINE_ANCHOR_Y = 2;
    private static final int ENGINE_FRAME_LOW_Y = 3;
    private static final int ENGINE_SCREEN_Y = 4;
    private static final int ENGINE_FRAME_HIGH_Y = 5;
    private static final int ENGINE_ALONG_MIN = 5;
    private static final int ENGINE_ALONG_MAX = 9;
    private static final int ENGINE_ANCHOR_ALONG = 7;

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
        net.minecraft.core.Direction facing = switch (wall) {
            case NORTH -> Direction.SOUTH; // doors on north wall, open/facing into room
            case SOUTH -> Direction.NORTH;
            case EAST -> Direction.WEST;
            case WEST -> Direction.EAST;
        };
        Identifier[] blocks = {DOOR_NONE, DOOR_OMINOUS, DOOR_GREATER_3};
        for (int i = 0; i < SELECTOR_DOORS.length; i++) {
            placeDoor(level, selectorDoorPos(o, wall, SELECTOR_DOORS[i]), blocks[i], facing,
                    DoorHingeSide.LEFT);
        }
    }

    /** Clears the three selector doors placed by {@link #placeSelectorDoors}. */
    static void clearSelectorDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        for (int pos : SELECTOR_DOORS) {
            BlockPos lower = selectorDoorPos(o, wall, pos);
            RoomBuilder.set(level, lower, Blocks.AIR.defaultBlockState());
            RoomBuilder.set(level, lower.above(), Blocks.AIR.defaultBlockState());
        }
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
        net.minecraft.core.Direction facing = switch (wall) {
            case NORTH -> Direction.SOUTH; // doors open into the room
            case SOUTH -> Direction.NORTH;
            case EAST -> Direction.WEST;
            case WEST -> Direction.EAST;
        };
        for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
            BlockPos lower = postSelectionDoorPos(o, wall, i);
            DoorHingeSide hinge = i == RoomGeometry.DOOR_MIN
                    ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            placeDoor(level, lower, DOOR_NONE, facing, hinge);
            RoomBuilder.set(level, lower.offset(0, 2, 0), RoomBuilder.WALL); // lintel
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
        RoomBuilder.set(level, doorPlanePos(o, wall, LEVER_ALONG, 2), leverState(wall));
        placeLeverSign(level, o, wall);
        for (int y = 4; y <= 5; y++) {
            for (int along = 4; along <= 11; along++) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, y), SCREEN_BLOCK);
            }
        }
        placeEngineBay(level, o, RoomGeometry.leftOf(wall));
    }

    /**
     * The engine bay: the anchor, its framed screen row, and the bezel. See
     * {@link #ENGINE_ANCHOR_Y} for the elevation of each course.
     */
    private static void placeEngineBay(ServerLevel level, BlockPos o, DoorMask.Direction engineWall) {
        RoomBuilder.set(level, wallRingPos(o, engineWall, ENGINE_ANCHOR_ALONG, ENGINE_ANCHOR_Y),
                ENGINE_BLOCK);
        for (int along = ENGINE_ALONG_MIN; along <= ENGINE_ALONG_MAX; along++) {
            RoomBuilder.set(level, wallRingPos(o, engineWall, along, ENGINE_SCREEN_Y), SCREEN_BLOCK);
            RoomBuilder.set(level, wallRingPos(o, engineWall, along, ENGINE_FRAME_HIGH_Y),
                    frameState(engineWall, Half.BOTTOM));
            RoomBuilder.set(level, wallRingPos(o, engineWall, along, ENGINE_FRAME_LOW_Y),
                    frameState(engineWall, Half.TOP));
        }
        RoomBuilder.set(level, wallRingPos(o, engineWall, ENGINE_ALONG_MIN - 1, ENGINE_SCREEN_Y),
                FRAME_END);
        RoomBuilder.set(level, wallRingPos(o, engineWall, ENGINE_ALONG_MAX + 1, ENGINE_SCREEN_Y),
                FRAME_END);
    }

    /**
     * Removes every M19 furniture block, restoring the room to its captured
     * shape: the lever and its sign were interior air, the bulbs, the door
     * screen, the engine block and the engine screen sat in the wall ring, so
     * each clears back to the block it displaced. Capture hygiene (trap 17 in
     * {@code DISCOVERIES.md}): none of it may bake into the owner's blob.
     *
     * <p>Unconditional where {@link #placeFurniture} is not: writing plain wall
     * over the bulb course is right whether a bulb or a doorway lintel is
     * standing there, since the lintel is that same stone brick.
     */
    static void clearFurniture(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        clearBulbs(level, o, wall);
        RoomBuilder.set(level, doorPlanePos(o, wall, LEVER_ALONG, 2), RoomBuilder.AIR);
        RoomBuilder.set(level, doorPlanePos(o, wall, LEVER_ALONG, SIGN_Y), RoomBuilder.AIR);
        for (int y = 4; y <= 5; y++) {
            for (int along = 4; along <= 11; along++) {
                RoomBuilder.set(level, wallRingPos(o, wall, along, y), RoomBuilder.WALL);
            }
        }
        DoorMask.Direction engineWall = RoomGeometry.leftOf(wall);
        RoomBuilder.set(level, wallRingPos(o, engineWall, ENGINE_ANCHOR_ALONG, ENGINE_ANCHOR_Y),
                RoomBuilder.WALL);
        for (int y = ENGINE_FRAME_LOW_Y; y <= ENGINE_FRAME_HIGH_Y; y++) {
            for (int along = ENGINE_ALONG_MIN - 1; along <= ENGINE_ALONG_MAX + 1; along++) {
                RoomBuilder.set(level, wallRingPos(o, engineWall, along, y), RoomBuilder.WALL);
            }
        }
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
     * beside it.
     */
    static void clearBulbs(ServerLevel level, BlockPos o, DoorMask.Direction wall) {
        for (int along : SELECTOR_DOORS) {
            RoomBuilder.set(level, wallRingPos(o, wall, along, BULB_Y), RoomBuilder.WALL);
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
        BlockPos pos = doorPlanePos(o, wall, LEVER_ALONG, SIGN_Y);
        RoomBuilder.set(level, pos, signState(wall));
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            PocketDungeonsMod.LOG.warn("The lever sign at {} did not come with a block entity", pos);
            return;
        }
        SignText text = new SignText()
                .setMessage(LEVER_SIGN_LINE, Component.literal(LEVER_SIGN_WORD))
                .setColor(DyeColor.WHITE)
                .setHasGlowingText(true);
        sign.setText(text, true);
        sign.setWaxed(true);
        sign.setChanged();
    }

    /** The commit lever's position, for click detection ({@code Instances.isCommitLever}). */
    static BlockPos leverPos(BlockPos o, DoorMask.Direction wall) {
        return doorPlanePos(o, wall, LEVER_ALONG, 2);
    }

    /** The engine block's position, for click detection ({@code Instances.engineTerminalAt}). */
    static BlockPos enginePos(BlockPos o, DoorMask.Direction selectorWall) {
        return wallRingPos(o, RoomGeometry.leftOf(selectorWall),
                ENGINE_ANCHOR_ALONG, ENGINE_ANCHOR_Y);
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
        Direction facing = switch (wall) {
            case NORTH -> Direction.SOUTH;
            case SOUTH -> Direction.NORTH;
            case EAST -> Direction.WEST;
            case WEST -> Direction.EAST;
        };
        return LEVER_OFF.setValue(LeverBlock.FACING, facing);
    }

    /**
     * The lever's sign, hung on {@code wall} and facing into the room. A wall
     * sign's FACING is the direction its face points, away from the block it
     * is attached to, so it reads the same way {@link #leverState} does.
     */
    private static BlockState signState(DoorMask.Direction wall) {
        Direction facing = switch (wall) {
            case NORTH -> Direction.SOUTH;
            case SOUTH -> Direction.NORTH;
            case EAST -> Direction.WEST;
            case WEST -> Direction.EAST;
        };
        return LEVER_SIGN.setValue(WallSignBlock.FACING, facing);
    }

    /**
     * One bezel course, facing into the room from {@code wall}. {@code half}
     * is what mirrors the two courses: BOTTOM above the screen, TOP below it,
     * so each one's solid half meets the screen row and its step turns away
     * from it.
     */
    private static BlockState frameState(DoorMask.Direction wall, Half half) {
        Direction facing = switch (wall) {
            case NORTH -> Direction.SOUTH;
            case SOUTH -> Direction.NORTH;
            case EAST -> Direction.WEST;
            case WEST -> Direction.EAST;
        };
        return FRAME.setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
    }

    // ---- spec ---------------------------------------------------------------

    private static final class RoomSpec {
        final String name;
        final Set<Direction> doors;
        List<BlockPos> chests = List.of();
        List<BlockPos> spawns = List.of();
        BlockPos spawner;
        boolean exitPad;
        BiConsumer<ServerLevel, BlockPos> decor;

        RoomSpec(String name, Set<Direction> doors) {
            this.name = name;
            this.doors = doors;
        }

        RoomSpec chests(BlockPos... positions) {
            this.chests = List.of(positions);
            return this;
        }

        RoomSpec spawns(BlockPos... positions) {
            this.spawns = List.of(positions);
            return this;
        }

        RoomSpec spawner(BlockPos position) {
            this.spawner = position;
            return this;
        }

        RoomSpec exitPad() {
            this.exitPad = true;
            return this;
        }

        RoomSpec decor(BiConsumer<ServerLevel, BlockPos> decor) {
            this.decor = decor;
            return this;
        }
    }

    private static BlockPos[] concat(BlockPos[] base, BlockPos... extra) {
        BlockPos[] out = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    // ---- building -----------------------------------------------------------

    private static void buildAndQueue(ServerLevel level, Path outDir, RoomSpec spec, int zOffset) {
        BlockPos o = new BlockPos(SCRATCH_X, BASE_Y, SCRATCH_Z + zOffset);
        forceChunks(level, o, true);

        buildCell(level, o, spec.doors);
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

        Path outFile = outDir.resolve(spec.name + ".nbt");
        queue.add(new Task(() -> {
            try {
                captureAndSave(level, o, outFile);
            } catch (Exception e) {
                PocketDungeonsMod.LOG.error("Failed to save template {}", spec.name, e);
            } finally {
                clear(level, o);
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
    private static void buildCell(ServerLevel level, BlockPos o, Set<Direction> doors) {
        RoomBuilder.buildShell(level, o, RoomBuilder.FLOOR);
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
    private static void column(ServerLevel level, BlockPos o, int x, int yFrom, int yTo, int z,
                               BlockState state) {
        for (int y = yFrom; y <= yTo; y++) {
            RoomBuilder.set(level, o.offset(x, y, z), state);
        }
    }

    /** Floor inlay ringing the centre of the cell. Purely cosmetic, never solid
     *  above the floor, so it cannot obstruct any route. */
    private static void floorRing(ServerLevel level, BlockPos o, BlockState state) {
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

    private static void captureAndSave(ServerLevel level, BlockPos o, Path outFile) throws IOException {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, o, TEMPLATE_SIZE, true, List.of());
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
            template.fillFromWorld(level, cellOrigin, TEMPLATE_SIZE, true, List.of());
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

    private static void clear(ServerLevel level, BlockPos o) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                for (int y = 0; y <= CEILING_Y; y++) {
                    RoomBuilder.set(level, o.offset(x, y, z), RoomBuilder.AIR);
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
