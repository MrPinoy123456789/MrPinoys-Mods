package pocketdungeons;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure regression for {@link HiddenOrePlanner}: a pocket never touches air, never sits
 * in the wall margin, stays inside its count and size bounds, and is stable per seed.
 */
public class HiddenOrePlannerTest {

    private static final int CELL = 16;
    private static final int WALL_HEIGHT = 5;

    public static void main(String[] args) {
        testNeverTouchesAirOrMargin();
        testBounds();
        testStablePerSeed();
        testNoRockNoPockets();
        System.out.println("HiddenOrePlannerTest passed");
    }

    /** A room of solid rock with a walkway tunnel through x 7 to 8 at y 1 to 3, and an air pit. */
    private static boolean rock(int x, int y, int z) {
        if (x < 0 || x >= CELL || z < 0 || z >= CELL || y < 0 || y > WALL_HEIGHT + 1) {
            return true; // the shell
        }
        boolean tunnel = x >= 7 && x <= 8 && y >= 1 && y <= 3;
        boolean pit = x >= 3 && x <= 4 && z >= 10 && z <= 11 && y >= 1 && y <= 2;
        return !tunnel && !pit;
    }

    private static void testNeverTouchesAirOrMargin() {
        int sawPockets = 0;
        for (long seed = 0; seed < 400; seed++) {
            for (List<int[]> pocket : HiddenOrePlanner.plan(seed, 0, 3, 1, 4, CELL, WALL_HEIGHT,
                    HiddenOrePlannerTest::rock, (x, y, z) -> true)) {
                sawPockets++;
                for (int[] at : pocket) {
                    check(at[0] >= HiddenOrePlanner.MARGIN && at[0] <= CELL - 1 - HiddenOrePlanner.MARGIN
                                    && at[2] >= HiddenOrePlanner.MARGIN && at[2] <= CELL - 1 - HiddenOrePlanner.MARGIN,
                            "never in the wall margin: " + java.util.Arrays.toString(at));
                    check(at[1] >= 1 && at[1] <= WALL_HEIGHT - 1, "inside the interior height");
                    check(rock(at[0], at[1], at[2]), "a pocket block stands in rock");
                    for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                        check(rock(at[0] + d[0], at[1] + d[1], at[2] + d[2]),
                                "a pocket never touches air: " + java.util.Arrays.toString(at));
                    }
                }
            }
        }
        check(sawPockets > 100, "pockets are placed at all (" + sawPockets + ")");
    }

    private static void testBounds() {
        for (long seed = 0; seed < 400; seed++) {
            List<List<int[]>> pockets = HiddenOrePlanner.plan(seed, 1, 2, 2, 3, CELL, WALL_HEIGHT,
                    HiddenOrePlannerTest::rock, (x, y, z) -> true);
            check(pockets.size() >= 1 && pockets.size() <= 2, "pocket count within bounds: " + pockets.size());
            Set<String> all = new HashSet<>();
            for (List<int[]> pocket : pockets) {
                check(pocket.size() >= 1 && pocket.size() <= 3, "pocket size within bounds: " + pocket.size());
                for (int[] at : pocket) {
                    check(all.add(java.util.Arrays.toString(at)), "no block is in two pockets");
                }
            }
        }
        // A fixed count and size in open rock is exactly that.
        List<List<int[]>> exact = HiddenOrePlanner.plan(5L, 2, 2, 3, 3, CELL, WALL_HEIGHT,
                (x, y, z) -> true, (x, y, z) -> true);
        check(exact.size() == 2 && exact.get(0).size() == 3 && exact.get(1).size() == 3, "2 pockets of 3 in open rock");
    }

    private static void testStablePerSeed() {
        for (long seed = 0; seed < 50; seed++) {
            List<List<int[]>> a = HiddenOrePlanner.plan(seed, 0, 2, 1, 3, CELL, WALL_HEIGHT,
                    HiddenOrePlannerTest::rock, (x, y, z) -> true);
            List<List<int[]>> b = HiddenOrePlanner.plan(seed, 0, 2, 1, 3, CELL, WALL_HEIGHT,
                    HiddenOrePlannerTest::rock, (x, y, z) -> true);
            check(a.size() == b.size(), "same seed, same pocket count");
            for (int i = 0; i < a.size(); i++) {
                check(a.get(i).size() == b.get(i).size(), "same seed, same pocket size");
                for (int j = 0; j < a.get(i).size(); j++) {
                    check(java.util.Arrays.equals(a.get(i).get(j), b.get(i).get(j)), "same seed, same positions");
                }
            }
        }
    }

    /** A hall of air, or rock a node already holds, leaves nothing to bury. */
    private static void testNoRockNoPockets() {
        check(HiddenOrePlanner.plan(1L, 2, 2, 1, 3, CELL, WALL_HEIGHT, (x, y, z) -> false, (x, y, z) -> true).isEmpty(),
                "an open hall has no pockets");
        check(HiddenOrePlanner.plan(1L, 2, 2, 1, 3, CELL, WALL_HEIGHT, (x, y, z) -> true, (x, y, z) -> false).isEmpty(),
                "ground that is already a node takes no pocket");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
