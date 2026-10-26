package charter.mc.justice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The flood fill behind marking a cell (PLAN.md §7.4, §10.2): from the block the
 * marker stands in, every connected block a body could pass through, in six
 * directions. A space is a cell only if the fill stops by itself inside the keep
 * and under the size limit. No Minecraft types, so a plain test runs it over a
 * made-up world.
 */
public final class CellFill {

    private CellFill() {
    }

    /** What the fill may ask of the world. */
    public interface Space {
        /** True if a body passes through this block (no collision). An unloaded block counts as passable. */
        boolean passable(int x, int y, int z);

        /** True if the block is in loaded ground of the keep; a fill that wanders off it has leaked. */
        boolean inKeep(int x, int y, int z);
    }

    /** The blocks of the cell, or the key of the reason it is refused. */
    public record Result(List<CellBook.Pos> blocks, String refusal) {
        public boolean ok() {
            return refusal == null;
        }
    }

    public static final String START_BLOCKED = "cell.start_blocked";
    public static final String LEAKS = "cell.leaks";
    public static final String TOO_BIG = "cell.too_big";

    public static Result fill(Space space, CellBook.Pos start, int maxBlocks) {
        if (!space.inKeep(start.x(), start.y(), start.z()) || !space.passable(start.x(), start.y(), start.z())
                || !space.passable(start.x(), start.y() + 1, start.z())) {
            return new Result(List.of(), START_BLOCKED);
        }
        Set<CellBook.Pos> seen = new HashSet<>();
        ArrayDeque<CellBook.Pos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            CellBook.Pos at = queue.poll();
            for (int[] d : around) {
                int x = at.x() + d[0];
                int y = at.y() + d[1];
                int z = at.z() + d[2];
                CellBook.Pos next = new CellBook.Pos(x, y, z);
                if (seen.contains(next)) continue;
                if (!space.inKeep(x, y, z)) {
                    // Off the keep's ground (or unloaded, which the space reports as passable): a wall is no leak.
                    if (space.passable(x, y, z)) return new Result(List.of(), LEAKS);
                    continue;
                }
                if (!space.passable(x, y, z)) continue;
                seen.add(next);
                if (seen.size() > maxBlocks) return new Result(List.of(), TOO_BIG);
                queue.add(next);
            }
        }
        List<CellBook.Pos> blocks = new ArrayList<>(seen);
        blocks.sort(null);
        return new Result(blocks, null);
    }
}
