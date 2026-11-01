package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/** Phase 4 pure checks: placement points, ring and separation, level, lifespan and the claims file. */
public final class WildPlacementTest {

    private static final String SAMPLE = "{\"version\":4,\"areas\":[{\"kind\":\"keep\",\"kingdom\":\"Azrak\","
            + "\"dimension\":\"minecraft:overworld\",\"bounds\":{\"minX\":-768,\"minZ\":-1744,\"maxX\":-625,"
            + "\"maxZ\":-1601},\"chunks\":[[-48,-109],[-48,-108],[-47,-109]]},{\"kind\":\"outpost\","
            + "\"kingdom\":\"Banner Bandits\",\"dimension\":\"minecraft:overworld\",\"bounds\":{\"minX\":1488,"
            + "\"minZ\":-112,\"maxX\":1503,\"maxZ\":-97},\"chunks\":[[93,-7]]}]}";

    private WildPlacementTest() {}

    public static void main(String[] args) {
        placement();
        ring();
        level();
        lifespan();
        claims();
        retired();
        System.out.println("WildPlacementTest passed");
    }

    private static void placement() {
        int cell = 1500;
        int[] a = WildPlacement.candidate(42L, 3, -2, 0, cell);
        int[] b = WildPlacement.candidate(42L, 3, -2, 0, cell);
        check(a[0] == b[0] && a[1] == b[1], "the same inputs give the same point");
        int[] c = WildPlacement.candidate(42L, 3, -2, 1, cell);
        check(a[0] != c[0] || a[1] != c[1], "a new generation gives another point");
        check(a[0] >= 3 * cell && a[0] < 4 * cell && a[1] >= -2 * cell && a[1] < -cell, "the point stays in its cell");
        // Uniform: the sample mean is near the cell centre and the spread near cell / sqrt(12).
        double sum = 0;
        double sumSq = 0;
        int n = 10000;
        for (int i = 0; i < n; i++) {
            double x = WildPlacement.candidate(7L, i, 0, 0, cell)[0] - (double) i * cell;
            sum += x;
            sumSq += x * x;
        }
        double mean = sum / n;
        double sd = Math.sqrt(sumSq / n - mean * mean);
        check(Math.abs(mean - cell / 2.0) < cell * 0.02, "the mean is near the centre: " + mean);
        check(Math.abs(sd - cell / Math.sqrt(12.0)) < cell * 0.02, "the spread is uniform: " + sd);
        // No lattice: not every point sits on a multiple of a coarse step.
        int off = 0;
        for (int i = 0; i < 200; i++) {
            if (WildPlacement.candidate(9L, i, i, 0, cell)[0] % 50 != 0) {
                off++;
            }
        }
        check(off > 150, "points do not sit on a lattice");
        check(WildPlacement.cellOf(-1, cell) == -1 && WildPlacement.cellOf(0, cell) == 0
                && WildPlacement.cellOf(1499, cell) == 0 && WildPlacement.cellOf(1500, cell) == 1, "cell index");
    }

    private static void ring() {
        List<int[]> others = new ArrayList<>();
        others.add(new int[] {1000, 1000});
        check(!WildPlacement.separated(1300, 1000, others, 600), "300 blocks away is too close");
        check(WildPlacement.separated(1700, 1000, others, 600), "700 blocks away is far enough");
        check(WildPlacement.separated(0, 0, new ArrayList<>(), 600), "nothing to keep away from");
        check(!WildPlacement.withinRing(100, 0, 0, 0, 500, 6000), "inside the minimum distance");
        check(WildPlacement.withinRing(3000, 0, 0, 0, 500, 6000), "inside the ring");
        check(!WildPlacement.withinRing(7000, 0, 0, 0, 500, 6000), "beyond the radius");
    }

    private static void level() {
        check(WildLevel.of(0, 5) == 1, "level 1 at spawn");
        check(WildLevel.of(399, 5) == 1 && WildLevel.of(400, 5) == 2, "a level per 400 blocks");
        check(WildLevel.of(100000, 5) == 5, "capped at the offering maximum");
        check(WildLevel.of(100000, 0) == 1, "a maximum below 1 still gives 1");
        check(WildLevel.of(-50, 5) == 1, "never below 1");
    }

    private static void lifespan() {
        long created = 1_000_000L;
        long e1 = WildLifespan.expiresAt(created, 7, "w1");
        check(e1 == WildLifespan.expiresAt(created, 7, "w1"), "the jitter is seeded by the id");
        check(e1 >= created + 7 * 86400L && e1 <= created + 7 * 86400L + WildLifespan.JITTER_SECONDS, "within the jitter");
        boolean differs = false;
        for (int i = 0; i < 20; i++) {
            differs |= WildLifespan.expiresAt(created, 7, "w" + i) != e1;
        }
        check(differs, "different ids get different jitter");
        long exp = 10_000_000L;
        check(WildLifespan.warningDue(exp - 100_000L, exp, 24) == 0, "no warning far out");
        check(WildLifespan.warningDue(exp - 3600L * 23, exp, 24) == 1, "the long warning");
        check(WildLifespan.warningDue(exp - 300L, exp, 24) == 2, "the ten minute warning");
        check(WildLifespan.warningDue(exp + 5L, exp, 24) == 2, "past the end counts as final");
    }

    private static void claims() {
        ClaimSource.Snapshot s = ClaimSource.parse(SAMPLE);
        check(s.isClaimedNear(-48 * 16 + 5, -109 * 16 + 5, 0), "a point inside a claimed chunk is claimed");
        check(s.isClaimedNear(-48 * 16 - 20, -109 * 16, 64), "the buffer reaches a neighbouring claimed chunk");
        check(!s.isClaimedNear(-48 * 16 - 100, -109 * 16, 16), "a far point is not claimed");
        check(!s.isClaimedNear(5000, 5000, 64), "empty land is not claimed");
        // The chunk edge: block 15 is chunk 0, block 16 is chunk 1; 1 block of buffer from 16 reaches chunk 0.
        ClaimSource.Snapshot edge = ClaimSource.parse("{\"areas\":[{\"kind\":\"claim\",\"dimension\":\"minecraft:overworld\","
                + "\"chunks\":[[0,0]]}]}");
        check(edge.isClaimedNear(16, 0, 1) && !edge.isClaimedNear(17, 0, 0), "the buffer arithmetic at a chunk edge");
        // An unknown kind still counts; a nether entry does not.
        ClaimSource.Snapshot mixed = ClaimSource.parse("{\"areas\":[{\"kind\":\"mystery\",\"dimension\":"
                + "\"minecraft:overworld\",\"chunks\":[[2,2]]},{\"kind\":\"keep\",\"dimension\":\"minecraft:the_nether\","
                + "\"chunks\":[[9,9]]}]}");
        check(mixed.isClaimedNear(32, 32, 0), "an unknown kind is claimed land");
        check(!mixed.isClaimedNear(9 * 16, 9 * 16, 0), "a nether entry is ignored");
        check(ClaimSource.parse("{\"areas\":[]}").chunks().isEmpty(), "an empty areas array is no claims");
        ClaimSource.Snapshot partial = ClaimSource.parse("{\"areas\":[{\"bad\":1},{\"dimension\":\"minecraft:overworld\","
                + "\"chunks\":[[1,1]]}]}");
        check(partial.isClaimedNear(16, 16, 0), "a bad entry is skipped and the rest read");
        expectUnusable("not json at all");
        expectUnusable("{\"nothing\":1}");
        expectUnusable("{\"areas\":[{\"x\":1},{\"y\":2}]}");
    }

    private static void retired() {
        WildState state = new WildState();
        WildState.Lodestone l = new WildState.Lodestone("w9", "minecraft:overworld", 10, 70, 20, "d", 2, 1L, 2L);
        l.lostAndFound.add(new int[] {11, 70, 21});
        state.add(l);
        state.retire("w9");
        check(state.get("w9") == null && state.retired().size() == 1, "retire moves a lodestone to the retired list");
        WildState back = WildState.fromJson(state.toJson());
        check(back.retired().size() == 1 && back.retired().get(0).id().equals("w9")
                && back.retired().get(0).chests().get(0)[2] == 21, "the retired list survives a save");
        check(WildState.fromJson("{\"version\":1,\"nextId\":1,\"lodestones\":[],\"players\":{}}").retired().isEmpty(),
                "an older file without the list loads");
    }

    private static void expectUnusable(String text) {
        try {
            ClaimSource.parse(text);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected an unusable claims file: " + text);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
