package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static pocketdungeons.RoomGeometry.CELL;
import static pocketdungeons.RoomGeometry.DOOR_MAX;
import static pocketdungeons.RoomGeometry.DOOR_MIN;
import static pocketdungeons.RoomGeometry.WALL_HEIGHT;
import static pocketdungeons.RoomTemplateGenerator.QUAD_SPAWNS;
import static pocketdungeons.RoomTemplateGenerator.concat;

/**
 * M50: the traversal family's room templates.
 *
 * <p>Rooms whose situation is getting across: a lava chasm, a flooded hall, a
 * powder snow field, a cobweb thicket, an ice run with wall spikes.
 *
 * <p>Three of the five are pure template (Chasm, Powder Snow Field, and the
 * decor parts of Thicket and Ice Run). Flooded Hall carries a Situations
 * handler that spawns drowned and places iron doors to contain the water.
 * Thicket and Ice Run each carry a classic spawner that their handlers set to
 * the room's own mob at stamp time (ClassicSpawners, PD-68).
 */
final class TraversalSpecs {

    private TraversalSpecs() {}

    private static boolean handlersRegistered;

    /**
     * Registers the traversal family's situation handlers. Called from
     * {@link TrialContent#warmUp} like every other family.
     *
     * <p>PD-86: this used to be a static initializer, which only runs when the
     * class is first touched, and on a live server nothing but
     * {@code /dungeon admin gentemplates} ({@link #list}) ever touched it. So
     * {@code thicket}, {@code ice_run} and {@code flooded_hall} were never
     * registered: their cells fell through to the plain corridor dispatch and
     * the baked zombie spawner stayed a zombie spawner that a lit room never
     * lets fire.
     */
    static void registerHandlers() {
        if (handlersRegistered) {
            return;
        }
        handlersRegistered = true;

        Situations.register("flooded_hall", TraversalSpecs::floodedHall);
        // PD-68: the baked classic spawner is a zombie spawner that a lit room
        // never lets fire; give it the room's own mob and light-free rules.
        // The Ordeal also sets the spawner to its mob (ClassicSpawners).
        Situations.register("thicket", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            Ordeals.arm(SpawnerOrdeal.THICKET, level, o);
            return null;
        });
        Situations.register("ice_run", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            Ordeals.arm(SpawnerOrdeal.ICE_RUN, level, o);
            return null;
        });
        // M58: template-only traversal rooms. The decor in the RoomSpec is
        // the whole of the content; the handler just owns the cell.
        Situations.register("chasm", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> { return null; });
        Situations.register("powder_snow_field", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> { return null; });
    }

    /** The traversal family's templates. */
    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();

        // Chasm: 6-wide lava channel across the room, kerbed, with a stone
        // ledge along the north wall at y=1 bridging the channel above the
        // lava. The kerb (x=4, x=11 at y=1) doubles as the walkway to the
        // ledge: enter at z=7..8, walk the west kerb north to z=1, cross
        // east on the ledge, walk the east kerb south to the exit.
        specs.add(new RoomSpec("chasm", EnumSet.of(Direction.WEST, Direction.EAST))
                .decor((level, o) -> {
                    BlockState lava = Blocks.LAVA.defaultBlockState();
                    BlockState stone = Blocks.STONE.defaultBlockState();
                    for (int x = 5; x <= 10; x++) {
                        for (int z = 1; z <= CELL - 2; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), lava);
                        }
                    }
                    for (int z = 1; z <= CELL - 2; z++) {
                        RoomBuilder.set(level, o.offset(4, 1, z), stone);
                        RoomBuilder.set(level, o.offset(11, 1, z), stone);
                    }
                    for (int x = 5; x <= 10; x++) {
                        RoomBuilder.set(level, o.offset(x, 1, 1), stone);
                    }
                }));

        // Flooded Hall: water to the ceiling from x=2 onward. The handler
        // places iron doors in open doorways to contain the water, a button
        // beside each to open it, and spawns two drowned. The glass window
        // is metadata driven (window: glass) so the player sees the flood
        // before stepping in.
        specs.add(new RoomSpec("flooded_hall", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(5, 1, 7), new BlockPos(10, 1, 8))
                .decor((level, o) -> {
                    BlockState water = Blocks.WATER.defaultBlockState();
                    for (int x = 2; x <= CELL - 2; x++) {
                        for (int z = 1; z <= CELL - 2; z++) {
                            for (int y = 1; y <= WALL_HEIGHT; y++) {
                                RoomBuilder.set(level, o.offset(x, y, z), water);
                            }
                        }
                    }
                }));

        // Powder Snow Field: powder snow two deep wall to wall, with 2x2
        // stone islands every four blocks along the crossing path. Leather
        // boots walk on the snow; without them the player sinks and freezes.
        specs.add(new RoomSpec("powder_snow_field", EnumSet.of(Direction.WEST, Direction.EAST))
                .decor((level, o) -> {
                    BlockState snow = Blocks.POWDER_SNOW.defaultBlockState();
                    BlockState stone = Blocks.STONE.defaultBlockState();
                    for (int x = 1; x <= CELL - 2; x++) {
                        for (int z = 1; z <= CELL - 2; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), snow);
                            RoomBuilder.set(level, o.offset(x, 1, z), snow);
                        }
                    }
                    int[][] islands = {{2, 7}, {6, 7}, {10, 7}, {13, 7}};
                    for (int[] island : islands) {
                        for (int dx = 0; dx < 2; dx++) {
                            for (int dz = 0; dz < 2; dz++) {
                                int x = island[0] + dx;
                                int z = island[1] + dz;
                                if (x > CELL - 2 || z > CELL - 2) continue;
                                RoomBuilder.set(level, o.offset(x, 0, z), stone);
                                RoomBuilder.set(level, o.offset(x, 1, z), stone);
                            }
                        }
                    }
                }));

        // Thicket: cobwebs floor to ceiling with a cave spider spawner in
        // the centre. The spawner is set to cave spiders at stamp time
        // (ClassicSpawners, PD-68).
        specs.add(new RoomSpec("thicket", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    BlockState web = Blocks.COBWEB.defaultBlockState();
                    // Playtest 2026-09-27: half the webs, as a 3D checkerboard.
                    // Playtest 2026-09-29-3, once the spawner fired: half again,
                    // "a kind of lattice". Every fourth diagonal plane: a quarter
                    // of the cells, and a two-block-tall player now meets a web
                    // on about half their steps rather than on every one.
                    for (int x = 1; x <= CELL - 2; x++) {
                        for (int z = 1; z <= CELL - 2; z++) {
                            for (int y = 1; y <= WALL_HEIGHT; y++) {
                                if ((x + y + z) % 4 != 0) {
                                    continue;
                                }
                                RoomBuilder.set(level, o.offset(x, y, z), web);
                            }
                        }
                    }
                    // The Ordeal's lever on the spawner's east face, its lamp
                    // above. Neither cell is on the web lattice.
                    Ordeals.placeWallLever(level, o.offset(9, 1, 8), Direction.EAST);
                }));

        // Ice Run (owner design, 2026-09-30): a climbing Ordeal. Floating
        // packed ice hops rise from each doorway to a 2x2 snow platform in
        // the middle of the room, three blocks up, with the Ordeal's lever on
        // top. Miss a hop and you land on the floor, where the higher hops
        // are out of reach: back to the start. A stray spawner on the floor
        // shoots you off the ice. Or pillar: three looted blocks from the
        // floor reach the platform, the room's vertical answer.
        //
        // Heights: the interior is y=1..5 under the ceiling at 6. Hops are
        // y=1 (top at 2) then y=2 (top at 3); the platform is y=3 (top at 4,
        // head clear at 5.8). The last jump up brushes the ceiling, which
        // still lands it. The course is symmetric under a half turn (x and z
        // both mirrored), so a turned room keeps a course from each door to
        // the middle and the lever can be baked.
        specs.add(new RoomSpec("ice_run", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawner(new BlockPos(8, 1, 2))
                .decor((level, o) -> {
                    BlockState floor = Blocks.PACKED_ICE.defaultBlockState();
                    BlockState hop = Blocks.PACKED_ICE.defaultBlockState();
                    BlockState platform = Blocks.SNOW_BLOCK.defaultBlockState();
                    BlockState spike = Blocks.POINTED_DRIPSTONE.defaultBlockState();
                    for (int x = 1; x <= CELL - 2; x++) {
                        for (int z = 1; z <= CELL - 2; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), floor);
                        }
                    }
                    // West approach, then the same course turned half round
                    // for the east: (x, z) -> (15 - x, 15 - z).
                    int[][] hops = {{2, 1, 7}, {4, 2, 8}, {6, 2, 6}};
                    for (int[] h : hops) {
                        RoomBuilder.set(level, o.offset(h[0], h[1], h[2]), hop);
                        RoomBuilder.set(level, o.offset(CELL - 1 - h[0], h[1], CELL - 1 - h[2]), hop);
                    }
                    for (int x = 7; x <= 8; x++) {
                        for (int z = 7; z <= 8; z++) {
                            RoomBuilder.set(level, o.offset(x, 3, z), platform);
                        }
                    }
                    // Dripstone under the gaps, so a fall from the ice hurts.
                    int[][] spikes = {{5, 7}, {7, 9}};
                    for (int[] s : spikes) {
                        RoomBuilder.set(level, o.offset(s[0], 1, s[1]), spike);
                        RoomBuilder.set(level, o.offset(CELL - 1 - s[0], 1, CELL - 1 - s[1]), spike);
                    }
                    // The Ordeal's lever stands on the platform; its lamp is
                    // the platform block under it, lit through the lever.
                    Ordeals.placeFloorLever(level, o.offset(8, 4, 8));
                }));

        return specs;
    }

    // ---- Flooded Hall handler -----------------------------------------------

    /**
     * Places a closed iron door in each open doorway to contain the water and
     * spawns two drowned from the template's spawn anchors. Audit 4.3:
     * full-height water cannot be held by a kerb, so the doorway itself is
     * sealed with a door the player opens on demand. PD-160: each door is a
     * latch ({@link IronDoorLatch}), opened by using it from either side; the
     * stone button it used to carry sat in the water column and was washed off,
     * and a player arriving from outside never had one.
     */
    private static BlockPos floodedHall(ServerLevel level, BlockPos cellOrigin, String role,
                                    int depth, DifficultyProfile profile, List<BlockPos> spawns,
                                    long seed, Set<String> affixes, String lootSuffix,
                                    String theme, boolean voidedFloor, String content) {
        for (Direction wall : new Direction[]{Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST}) {
            if (!isDoorwayOpen(level, cellOrigin, wall)) continue;
            placeContainmentDoor(level, cellOrigin, wall);
        }
        RoomContent.spawnMobs(level, cellOrigin, EntityTypes.DROWNED, 2,
                spawns.isEmpty() ? defaultDrownedSpawns(cellOrigin) : spawns, seed, null);
        return null;
    }

    /**
     * Whether the canonical doorway slot on {@code wall} is open (air at floor
     * level), meaning the template punched a door there and the room needs to
     * contain its fluid behind one.
     */
    private static boolean isDoorwayOpen(ServerLevel level, BlockPos o, Direction wall) {
        BlockPos pos = wallPos(o, wall, DOOR_MIN, 1);
        return level.getBlockState(pos).isAir();
    }

    /**
     * A closed iron door pair in the doorway plane and a wall-block lintel at
     * y=3. No button: the door is a latch ({@link IronDoorLatch}).
     */
    private static void placeContainmentDoor(ServerLevel level, BlockPos o, Direction wall) {
        Direction facing = wall.getOpposite();
        BlockState cap = RoomBuilder.WALL;
        for (int i = DOOR_MIN; i <= DOOR_MAX; i++) {
            DoorHingeSide hinge = i == DOOR_MIN ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, facing)
                    .setValue(DoorBlock.HINGE, hinge)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.OPEN, false);
            BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            RoomBuilder.set(level, wallPos(o, wall, i, 1), lower);
            RoomBuilder.set(level, wallPos(o, wall, i, 2), upper);
            RoomBuilder.set(level, wallPos(o, wall, i, 3), cap);
        }
    }

    /**
     * PD-160: the lower halves of the containment doors a flooded hall stamped
     * at {@code cellOrigin}, for the layout's latch set. Read back from the
     * world rather than returned by the situation handler, which has no channel
     * for them.
     */
    static List<BlockPos> latchDoorsAt(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> doors = new ArrayList<>();
        for (Direction wall : new Direction[]{Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST}) {
            for (int i = DOOR_MIN; i <= DOOR_MAX; i++) {
                BlockPos pos = wallPos(cellOrigin, wall, i, 1);
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    doors.add(pos.immutable());
                }
            }
        }
        return doors;
    }

    /** A position on the wall ring at column {@code i}, height {@code y}. */
    private static BlockPos wallPos(BlockPos o, Direction wall, int i, int y) {
        return switch (wall) {
            case NORTH -> o.offset(i, y, 0);
            case SOUTH -> o.offset(i, y, CELL - 1);
            case WEST -> o.offset(0, y, i);
            case EAST -> o.offset(CELL - 1, y, i);
            default -> throw new IllegalArgumentException("wall must be horizontal: " + wall);
        };
    }

    /** Fallback spawn anchors if the template carried none. */
    private static List<BlockPos> defaultDrownedSpawns(BlockPos o) {
        return List.of(o.offset(5, 1, 7), o.offset(10, 1, 8));
    }
}
