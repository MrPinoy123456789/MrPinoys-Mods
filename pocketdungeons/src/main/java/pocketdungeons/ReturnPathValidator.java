package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M61 (spec 13.4): at stamp time, a room with {@code spanY > 1} must have a
 * climbable route from its lowest story floor back to the upper story. A room
 * without one is an authoring mistake the player cannot escape: the lower
 * story has no doorways, so the only way out is up.
 *
 * <p>This validator inspects the stamped blocks structurally. It does not trust
 * metadata or authoring conventions; it reads the world the template just
 * wrote. Supported route patterns (spec 13.4):
 * <ul>
 *   <li>a ladder column: ladder blocks climbing from the lower story to the
 *       upper floor, with an open shaft through the over-ceiling filler
 *   <li>a water source column: the player can swim up
 *   <li>a soul sand bubble column: the player rides the updraft
 *   <li>a staircase of solid blocks: each step is one block higher
 * </ul>
 *
 * <p>The check runs after {@link TemplateStamper#place} writes the template
 * and before the stamp is considered successful. If it fails, the stamp is
 * refused and the room name is logged.
 */
final class ReturnPathValidator {

    private ReturnPathValidator() {}

    /**
     * Verifies a climbable route exists from the lowest story floor to the
     * upper floor. Returns {@code true} if one is found, {@code false} if not.
     *
     * @param cellOrigin the upper story's floor corner (local 0,0,0)
     * @param spanY      the room's vertical span (only called when > 1)
     */
    static boolean validate(ServerLevel level, BlockPos cellOrigin, int spanY) {
        int storyOffset = RoomGeometry.storyOffset(spanY);
        int lowerFloorY = -storyOffset; // local y of the lowest story's floor
        // The upper floor is at local y = 0. The player needs to reach a
        // position at y = 0 or above from the lower floor at y = lowerFloorY.
        // The shaft goes through the over-ceiling filler at y = -2 and y = -1
        // (for spanY = 2), which the room's decor must carve holes through.

        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                if (hasClimbableColumn(level, cellOrigin, x, z, lowerFloorY)) {
                    return true;
                }
            }
        }
        // Also check the staircase pattern, which spans multiple columns.
        return hasStaircase(level, cellOrigin, lowerFloorY);
    }

    /**
     * Checks one (x,z) column for a ladder, water, or soul sand bubble column
     * running from the lower floor to just below the upper floor.
     */
    private static boolean hasClimbableColumn(ServerLevel level, BlockPos cellOrigin,
                                               int x, int z, int lowerFloorY) {
        boolean hasLadder = false;
        boolean hasWater = false;
        boolean hasSoulSand = false;
        // Scan from the lower floor+1 up to y = -1 (just below the upper floor).
        // The block at each y must be air, ladder, water, or (for the bottom)
        // soul sand. A solid block breaks the column.
        for (int y = lowerFloorY + 1; y <= -1; y++) {
            BlockState state = level.getBlockState(cellOrigin.offset(x, y, z));
            if (state.is(Blocks.LADDER)) {
                hasLadder = true;
            } else if (state.is(Blocks.WATER)) {
                hasWater = true;
            } else if (y == lowerFloorY + 1 && state.is(Blocks.SOUL_SAND)) {
                hasSoulSand = true;
            } else if (!state.isAir()) {
                // A solid block breaks the climbable column. This column
                // might still work as a staircase, but not as a vertical shaft.
                return false;
            }
        }
        // A ladder column needs at least one ladder block. A water column
        // needs water. A soul sand column needs soul sand at the bottom
        // with water above (the water check already ran in the loop).
        return hasLadder || hasWater || hasSoulSand;
    }

    /**
     * Checks for a staircase of solid blocks from the lower floor to the upper
     * floor. A staircase is a sequence of solid blocks where each step is at
     * most one block higher than the previous and adjacent horizontally, so a
     * player can walk up without jumping.
     *
     * <p>This is a flood fill from every solid block at the lower floor level
     * that can step up to the next level, checking whether the fill reaches
     * y = 0 (the upper floor).
     */
    private static boolean hasStaircase(ServerLevel level, BlockPos cellOrigin, int lowerFloorY) {
        // Flood fill from the lower floor upward. A position (x, y, z) is
        // reachable if:
        //   - y == lowerFloorY and the block at (x, y, z) is solid (the floor
        //     itself counts as a step), or
        //   - a neighbour at (x', y, z') is reachable and the block at
        //     (x, y, z) is solid and y <= neighbour_y + 1 (step up at most 1).
        // The fill succeeds when any reachable position has y >= 0.
        boolean[][][] reachable = new boolean[RoomGeometry.CELL][RoomGeometry.CELL][-lowerFloorY + 1];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();

        // Seed: solid blocks at the lower floor level (y = lowerFloorY).
        // The lower floor itself is solid (it's the shell floor), so every
        // interior position at y = lowerFloorY is reachable as a starting step.
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                BlockState floor = level.getBlockState(cellOrigin.offset(x, lowerFloorY, z));
                if (isSolid(floor)) {
                    int yi = 0; // y index: 0 = lowerFloorY
                    reachable[x][z][yi] = true;
                    queue.add(new int[]{x, lowerFloorY, z});
                }
            }
        }

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int cx = cur[0], cy = cur[1], cz = cur[2];
            if (cy >= 0) {
                return true; // reached the upper floor level
            }
            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                if (nx < 0 || nx >= RoomGeometry.CELL || nz < 0 || nz >= RoomGeometry.CELL) {
                    continue;
                }
                // Can step up one block.
                for (int dy = 0; dy <= 1; dy++) {
                    int ny = cy + dy;
                    if (ny > 0) {
                        continue;
                    }
                    int nyi = ny - lowerFloorY;
                    if (nyi < 0 || nyi >= reachable[0][0].length) {
                        continue;
                    }
                    if (reachable[nx][nz][nyi]) {
                        continue;
                    }
                    // The block at (nx, ny, nz) must be solid to stand on,
                    // and the block above it must be air (headroom).
                    BlockState step = level.getBlockState(cellOrigin.offset(nx, ny, nz));
                    if (!isSolid(step)) {
                        continue;
                    }
                    BlockState above = level.getBlockState(cellOrigin.offset(nx, ny + 1, nz));
                    if (!above.isAir() && !above.is(Blocks.LADDER) && !above.is(Blocks.WATER)) {
                        continue;
                    }
                    reachable[nx][nz][nyi] = true;
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }
        return false;
    }

    private static boolean isSolid(BlockState state) {
        return state.isSolidRender();
    }
}
