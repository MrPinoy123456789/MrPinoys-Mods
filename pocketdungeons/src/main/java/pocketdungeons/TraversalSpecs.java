package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
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

    static {
        Situations.register("flooded_hall", TraversalSpecs::floodedHall);
        // PD-68: the baked classic spawner is a zombie spawner that a lit room
        // never lets fire; give it the room's own mob and light-free rules.
        Situations.register("thicket", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            ClassicSpawners.configure(level, o, EntityTypes.CAVE_SPIDER);
            return null;
        });
        Situations.register("ice_run", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            ClassicSpawners.configure(level, o, EntityTypes.BREEZE);
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
                    // Playtest 2026-09-27: half the webs. A 3D checkerboard
                    // keeps every step slow without walling the room solid.
                    for (int x = 1; x <= CELL - 2; x++) {
                        for (int z = 1; z <= CELL - 2; z++) {
                            for (int y = 1; y <= WALL_HEIGHT; y++) {
                                if ((x + y + z) % 2 != 0) {
                                    continue;
                                }
                                RoomBuilder.set(level, o.offset(x, y, z), web);
                            }
                        }
                    }
                }));

        // Ice Run: blue ice floor with stone ledges along the north and
        // south walls at y=3, pointed dripstone spikes at floor level beside
        // the walls, and a breeze spawner on the north ledge. The spawner
        // is set to breezes at stamp time (ClassicSpawners, PD-68).
        specs.add(new RoomSpec("ice_run", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawner(new BlockPos(4, 4, 2))
                .decor((level, o) -> {
                    BlockState blueIce = Blocks.BLUE_ICE.defaultBlockState();
                    BlockState stone = Blocks.STONE.defaultBlockState();
                    BlockState spike = Blocks.POINTED_DRIPSTONE.defaultBlockState();
                    for (int x = 1; x <= CELL - 2; x++) {
                        for (int z = 1; z <= CELL - 2; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), blueIce);
                        }
                    }
                    for (int x = 1; x <= CELL - 2; x++) {
                        RoomBuilder.set(level, o.offset(x, 3, 1), stone);
                        RoomBuilder.set(level, o.offset(x, 3, CELL - 2), stone);
                    }
                    for (int x = 2; x <= CELL - 3; x++) {
                        RoomBuilder.set(level, o.offset(x, 1, 2), spike);
                        RoomBuilder.set(level, o.offset(x, 1, CELL - 3), spike);
                    }
                }));

        return specs;
    }

    // ---- Flooded Hall handler -----------------------------------------------

    /**
     * Places a closed iron door in each open doorway to contain the water, a
     * stone button beside it to let the player through, and spawns two drowned
     * from the template's spawn anchors. Audit 4.3: full-height water cannot be
     * held by a kerb, so the doorway itself is sealed with a door the player
     * opens on demand.
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
     * A closed iron door pair in the doorway plane, a wall-block lintel at
     * y=3, and a stone button one block inside the room beside the door. The
     * button is adjacent to the door leaf and powers it when pressed.
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
        BlockState button = Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(ButtonBlock.FACE, AttachFace.WALL)
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing);
        RoomBuilder.set(level, insidePos(o, wall, DOOR_MIN - 1, 2), button);
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

    /** A position one block inside the room from {@code wall}, at column {@code i}, height {@code y}. */
    private static BlockPos insidePos(BlockPos o, Direction wall, int i, int y) {
        return switch (wall) {
            case NORTH -> o.offset(i, y, 1);
            case SOUTH -> o.offset(i, y, CELL - 2);
            case WEST -> o.offset(1, y, i);
            case EAST -> o.offset(CELL - 2, y, i);
            default -> throw new IllegalArgumentException("wall must be horizontal: " + wall);
        };
    }

    /** Fallback spawn anchors if the template carried none. */
    private static List<BlockPos> defaultDrownedSpawns(BlockPos o) {
        return List.of(o.offset(5, 1, 7), o.offset(10, 1, 8));
    }
}
