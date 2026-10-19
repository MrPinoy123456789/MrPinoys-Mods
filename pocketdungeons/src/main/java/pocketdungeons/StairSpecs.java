package pocketdungeons;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Vertical floors phase 2 (DECISIONS 2026-10-17, rule 2): the generic stair rooms. Each is a two story room
 * entered on its top story by a west door and left by a door on the story below: east (straight), north or
 * south (a turn). The way between is a real staircase two blocks wide that mobs and players share; there is no
 * ladder on it. A stair room requires nothing, provides nothing and carries no spawn, chest or spawner, so it can
 * sit on the spine of any dungeon that opts in to stories.
 *
 * <h2>Geometry (local, relative to the top story's cell origin)</h2>
 *
 * <p>The top floor is y=0 and the lower floor is y=-8 ({@link RoomGeometry#STORY_HEIGHT}). The stair runs east
 * from x=4 to x=11 over z=7..8 and drops one block per column: step {@code k} is a stair block at
 * {@code (4 + k, -k)} facing west (the high side toward the way in). Under each step is solid wall down to the lower
 * floor, so the run reads as one mass. The steps go down into the floor, so the columns above them are cleared:
 * rows {@code -k+1} to {@code -k+3}. A walking mob steps sideways at its current height before it drops, so it
 * needs three clear rows over the next step, not two (a probe with a husk showed two rows leave the run
 * unwalkable under the filler layer). That opens a well three long in the top floor (x=5..7) and cuts the lower
 * story's ceiling and filler layers over x=8..9. The bottom step lands on the lower floor at x=12.
 *
 * <p>Door lanes are kept: the top door at x=0 has x 1..3 clear on z 7..8; the lower doors have their own lane
 * clear on the lower floor. The stair block is the one stair material (stone brick stairs); the wall, floor,
 * accent and lamp placeholders are the four the theme processors re-skin, as for every generic room.
 */
final class StairSpecs {

    private StairSpecs() {}

    private static final int STORY = RoomGeometry.STORY_HEIGHT;
    /** Local y of the lower story's floor. */
    private static final int LOWER_FLOOR = -STORY;
    private static final BlockState BRICK_WALL = Blocks.STONE_BRICK_WALL.defaultBlockState();

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(straight());
        specs.add(gallery());
        specs.add(turn("stair_turn_north", Direction.NORTH));
        specs.add(turn("stair_turn_south", Direction.SOUTH));
        return specs;
    }

    private static BlockState step() {
        return Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
    }

    /** West door in, one straight run down, east door out one story below. */
    private static RoomSpec straight() {
        return new RoomSpec("stair_straight", EnumSet.of(Direction.WEST))
                .spanY(2)
                .lowerDoors(Direction.EAST)
                .decor((level, o) -> {
                    run(level, o);
                    lamps(level, o);
                });
    }

    /** As the straight stair, with accent pillars and low walls on the top story. */
    private static RoomSpec gallery() {
        return new RoomSpec("stair_gallery", EnumSet.of(Direction.WEST))
                .spanY(2)
                .lowerDoors(Direction.EAST)
                .decor((level, o) -> {
                    run(level, o);
                    lamps(level, o);
                    // Pillars either side of the well, clear of the door lane (z 7..8) and the stair columns.
                    for (int x : new int[] {3, 8}) {
                        for (int z : new int[] {4, 11}) {
                            RoomDsl.column(level, o, x, 1, 4, z, BRICK_WALL);
                            RoomDsl.put(level, o, x, 5, z, RoomDsl.ACCENT);
                        }
                    }
                    // Low walls guard the open well on the top floor.
                    RoomDsl.box(level, o, 5, 1, 6, 7, 1, 6, BRICK_WALL);
                    RoomDsl.box(level, o, 5, 1, 9, 7, 1, 9, BRICK_WALL);
                    // An accent band along the lower floor under the well.
                    RoomDsl.box(level, o, 13, LOWER_FLOOR, 5, 13, LOWER_FLOOR, 10, RoomDsl.ACCENT);
                });
    }

    /** West door in, a run down, then north or south out one story below. */
    private static RoomSpec turn(String name, Direction lowerDoor) {
        return new RoomSpec(name, EnumSet.of(Direction.WEST))
                .spanY(2)
                .lowerDoors(lowerDoor)
                .decor((level, o) -> {
                    run(level, o);
                    lamps(level, o);
                    // A landing inlay where the run meets the floor, then toward the door.
                    RoomDsl.box(level, o, 12, LOWER_FLOOR, 7, 13, LOWER_FLOOR, 8, RoomDsl.ACCENT);
                    int z = lowerDoor == Direction.NORTH ? 3 : 12;
                    RoomDsl.put(level, o, 7, -3, z, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 8, -3, z, RoomDsl.LAMP);
                });
    }

    /** The eight steps, their mass, and the headroom cut above them (three rows, see the class note). */
    private static void run(ServerLevel level, BlockPos o) {
        for (int k = 0; k < 8; k++) {
            int x = 4 + k;
            for (int z = 7; z <= 8; z++) {
                if (k < 7) {
                    RoomDsl.box(level, o, x, LOWER_FLOOR + 1, z, x, -k - 1, z, RoomDsl.WALL);
                }
                RoomDsl.put(level, o, x, -k, z, step());
                if (k >= 1) {
                    RoomDsl.box(level, o, x, -k + 1, z, x, -k + 3, z, RoomDsl.AIR);
                }
            }
        }
    }

    /** Light the top landing, the well and the foot of the stairs. */
    private static void lamps(ServerLevel level, BlockPos o) {
        RoomDsl.put(level, o, 6, 5, 4, RoomDsl.LAMP);
        RoomDsl.put(level, o, 6, 5, 11, RoomDsl.LAMP);
        RoomDsl.put(level, o, 12, -3, 4, RoomDsl.LAMP);
        RoomDsl.put(level, o, 12, -3, 11, RoomDsl.LAMP);
    }
}
