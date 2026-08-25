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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
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
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Room-template generator. Builds each room in a scratch region of the void
 * dimension, captures it with {@link StructureTemplate#fillFromWorld}, and
 * writes the .nbt file to
 * {@code src/main/resources/data/pocketdungeons/structure/rooms/}.
 *
 * <p>Entity capture is deferred by one tick so the level's spatial index has
 * time to pick up anything freshly spawned (see PLAN.md's round-trip note).
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

    private static final BlockState FLOOR = Blocks.POLISHED_ANDESITE.defaultBlockState();
    private static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState CEILING = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState LAMP = Blocks.SEA_LANTERN.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

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
        Path outDir = FabricLoader.getInstance().getGameDir().getParent().resolve(
                "src/main/resources/data/" + PocketDungeonsMod.MOD_ID + "/structure/rooms");
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not create template output directory", e);
            return;
        }

        List<RoomSpec> specs = specs();
        for (int i = 0; i < specs.size(); i++) {
            buildAndQueue(level, outDir, specs.get(i), i * SCRATCH_PITCH);
        }

        PocketDungeonsMod.LOG.info("Queued {} pocketdungeons room templates for capture", specs.size());
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
                    placeCornerLeavePad(level, o);
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
                    BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
                    column(level, o, 5, 1, WALL_HEIGHT, 5, brick);
                    column(level, o, 10, 1, WALL_HEIGHT, 5, brick);
                    column(level, o, 5, 1, WALL_HEIGHT, 10, brick);
                    column(level, o, 10, 1, WALL_HEIGHT, 10, brick);
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
                    RoomBuilder.set(level, o.offset(5, 0, 5), LAMP);
                    RoomBuilder.set(level, o.offset(10, 0, 5), LAMP);
                    RoomBuilder.set(level, o.offset(5, 0, 10), LAMP);
                    RoomBuilder.set(level, o.offset(10, 0, 10), LAMP);
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
                    placeCornerLeavePad(level, o);
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
    private static final Identifier DOOR_FRAGILE = Identifier.parse("minecraft:exposed_copper_door");

    /**
     * Where the three selector doors stand along their wall: the 2-wide slot
     * itself ({@link RoomGeometry#DOOR_MIN}..{@code DOOR_MAX}) plus one block of
     * plain wall beside it, so the strip is contiguous.
     */
    private static final int[] SELECTOR_DOORS = {7, 8, 9};

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
        Identifier[] blocks = {DOOR_NONE, DOOR_OMINOUS, DOOR_FRAGILE};
        for (int i = 0; i < SELECTOR_DOORS.length; i++) {
            placeDoor(level, selectorDoorPos(o, wall, SELECTOR_DOORS[i]), blocks[i], facing);
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
                                  net.minecraft.core.Direction facing) {
        Block block = BuiltInRegistries.BLOCK.getValue(blockId);
        BlockState lowerState = block.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.OPEN, false);
        RoomBuilder.set(level, lower, lowerState);
        RoomBuilder.set(level, lower.above(), lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /**
     * The lobby's leave-pad: a single lodestone tucked into the corner nearest
     * the entrance slot, with chiselled stone on the floor and wall face around
     * it so it reads as a built alcove rather than a stray block. Authored at a
     * fixed local position rather than the rotation-invariant centre
     * {@link #placeExitPad} uses -- the lobby is never rotated, so nothing
     * forces the pad to the centre square.
     */
    static void placeCornerLeavePad(ServerLevel level, BlockPos o) {
        int lx = 2;
        int lz = 1;
        BlockState ring = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        RoomBuilder.set(level, o.offset(lx, 0, lz), Blocks.LODESTONE.defaultBlockState());
        RoomBuilder.set(level, o.offset(lx - 1, 0, lz), ring);
        RoomBuilder.set(level, o.offset(lx + 1, 0, lz), ring);
        RoomBuilder.set(level, o.offset(lx, 0, lz + 1), ring);
        RoomBuilder.set(level, o.offset(lx - 1, 0, lz + 1), ring);
        RoomBuilder.set(level, o.offset(lx, 1, lz - 1), ring); // wall face behind the pad
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

    private static void buildCell(ServerLevel level, BlockPos o, Set<Direction> doors) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                boolean edge = x == 0 || x == CELL - 1 || z == 0 || z == CELL - 1;
                RoomBuilder.set(level, o.offset(x, 0, z), FLOOR);
                RoomBuilder.set(level, o.offset(x, CEILING_Y, z), CEILING);
                for (int y = 1; y <= WALL_HEIGHT; y++) {
                    BlockPos pos = o.offset(x, y, z);
                    Direction doorDir = edge ? RoomGeometry.wallDirection(x, z) : null;
                    if (doorDir != null && doors.contains(doorDir) && isDoorSlot(x, y, z, doorDir)) {
                        placeJigsaw(level, pos, DOOR, orientationFor(doorDir));
                    } else {
                        RoomBuilder.set(level, pos, edge ? WALL : AIR);
                    }
                }
            }
        }
        RoomBuilder.set(level, o.offset(4, CEILING_Y, 4), LAMP);
        RoomBuilder.set(level, o.offset(4, CEILING_Y, CELL - 5), LAMP);
        RoomBuilder.set(level, o.offset(CELL - 5, CEILING_Y, 4), LAMP);
        RoomBuilder.set(level, o.offset(CELL - 5, CEILING_Y, CELL - 5), LAMP);
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

    private static void clear(ServerLevel level, BlockPos o) {
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                for (int y = 0; y <= CEILING_Y; y++) {
                    RoomBuilder.set(level, o.offset(x, y, z), AIR);
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
