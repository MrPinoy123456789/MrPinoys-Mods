package pocketdungeons;

import java.util.List;

/** Roadmap T21: the coffin counts, the dealing, the wake rule and the awake cap of the Ossuary finale. */
public class OssuaryRulesTest {

    public static void main(String[] args) {
        int[] bases = {5, 2};

        check(sum(OssuaryRules.waveCounts(bases, 1, 50)) == 7, "solo: 5 + 2 = 7");
        check(sum(OssuaryRules.waveCounts(bases, 2, 50)) == 11, "two members: 8 + 3 = 11");
        for (int party = 1; party <= 12; party++) {
            int[] counts = OssuaryRules.waveCounts(bases, party, 50);
            check(sum(counts) <= OssuaryRules.COFFIN_WAVE_CAP, "party " + party + " stays under the cap");
            check(sum(counts) <= FinaleRules.MAX_WAVE, "party " + party + " stays under the finale cap");
            check(counts[0] > 0 && counts[1] > 0, "party " + party + " keeps both groups");
        }
        check(sum(OssuaryRules.waveCounts(bases, 12, 50)) == OssuaryRules.COFFIN_WAVE_CAP, "a huge party fills the cap exactly");

        check(OssuaryRules.coffinsUsed(6, 7) == 6, "six found, seven mobs: six used");
        check(OssuaryRules.coffinsUsed(6, 3) == 3, "fewer mobs than coffins: only as many as mobs");
        check(OssuaryRules.coffinsUsed(12, 40) == OssuaryRules.MAX_COFFINS, "twelve found: the knob caps it");
        check(OssuaryRules.coffinsUsed(0, 7) == 0, "none found: none used");

        int[] occ = OssuaryRules.occupants(7, 6);
        check(sum(occ) == 7 && occ[0] == 2 && occ[1] == 1 && occ[5] == 1, "7 into 6: 2,1,1,1,1,1");
        occ = OssuaryRules.occupants(24, 8);
        check(sum(occ) == 24 && occ[0] == 3 && occ[7] == 3, "24 into 8: three each");
        check(OssuaryRules.occupants(5, 0).length == 0, "no coffins deals nothing");

        int[] seq = OssuaryRules.sequence(new int[]{5, 2});
        check(seq.length == 7, "sequence has one slot per mob");
        check(count(seq, 0) == 5 && count(seq, 1) == 2, "sequence keeps the group totals");
        check(!(seq[5] == 1 && seq[6] == 1), "the zombies are not both at the end");
        int[] dealt = OssuaryRules.occupants(7, 6);
        List<Integer> first = OssuaryRules.slice(seq, dealt, 0);
        check(first.size() == 2, "slice gives coffin 0 its two");
        int covered = 0;
        for (int c = 0; c < 6; c++) {
            covered += OssuaryRules.slice(seq, dealt, c).size();
        }
        check(covered == 7, "the slices cover the whole sequence");

        double r = OssuaryRules.WAKE_RADIUS;
        check(OssuaryRules.shouldWake(r * r, 0), "exactly at the radius wakes it");
        check(!OssuaryRules.shouldWake(r * r + 1.0, 0), "just outside, nothing yet");
        check(!OssuaryRules.shouldWake(Double.MAX_VALUE, OssuaryRules.SKIP_DELAY_TICKS - 1), "far and early: still shut");
        check(OssuaryRules.shouldWake(Double.MAX_VALUE, OssuaryRules.SKIP_DELAY_TICKS), "far but the delay passed: opens");
        // The boss's second phase wakes by approach only; the third adds the skip rule (roadmap T03).
        check(OssuaryRules.shouldWake(r * r, 0, false), "phase two: a near member wakes it");
        check(!OssuaryRules.shouldWake(Double.MAX_VALUE, OssuaryRules.SKIP_DELAY_TICKS, false),
                "phase two: time alone opens nothing");
        check(OssuaryRules.shouldWake(Double.MAX_VALUE, OssuaryRules.SKIP_DELAY_TICKS, true),
                "phase three: the skipped coffin opens");
        check(OssuaryRules.choose(new double[]{900.0}, new boolean[]{true}, 9999, false) == -1,
                "phase two: a far coffin stays shut however long");
        check(OssuaryRules.choose(new double[]{900.0}, new boolean[]{true}, 9999, true) == 0,
                "phase three: the same coffin opens");

        double[] dist = {100.0, 4.0, 50.0};
        check(OssuaryRules.choose(dist, new boolean[]{true, true, true}, 0) == 1, "the nearest near coffin wakes");
        check(OssuaryRules.choose(dist, new boolean[]{true, false, true}, 0) == -1, "the near one is open; the rest are far");
        check(OssuaryRules.choose(dist, new boolean[]{true, false, true}, OssuaryRules.SKIP_DELAY_TICKS) == 2,
                "after the delay the nearest remaining sealed coffin opens");
        check(OssuaryRules.choose(dist, new boolean[]{false, false, false}, 9999) == -1, "nothing sealed");
        check(OssuaryRules.choose(new double[0], new boolean[0], 9999) == -1, "no coffins");

        check(!OssuaryRules.mayStart(0, 0, 1, OssuaryRules.MIN_GAP_TICKS - 1), "two never crack together");
        check(OssuaryRules.mayStart(0, 0, 1, OssuaryRules.MIN_GAP_TICKS), "after the gap it may");
        check(OssuaryRules.mayStart(OssuaryRules.AWAKE_CAP - 1, 0, 1, 1000), "one under the cap: one more may");
        check(!OssuaryRules.mayStart(OssuaryRules.AWAKE_CAP, 0, 1, 1000), "at the cap: wait");
        check(!OssuaryRules.mayStart(OssuaryRules.AWAKE_CAP - 2, 1, 2, 1000), "cracking counts toward the cap");
        check(OssuaryRules.mayStart(0, 0, OssuaryRules.AWAKE_CAP + 3, 1000), "a big coffin opens when all is quiet");

        check(OssuaryRules.CRACK_TICKS >= 40, "the tell runs at least 40 ticks");
        check(!OssuaryRules.crackDone(OssuaryRules.CRACK_TICKS - 1) && OssuaryRules.crackDone(OssuaryRules.CRACK_TICKS),
                "the lid breaks exactly when the tell ends");
        check(OssuaryRules.MAX_COFFINS >= 6 && OssuaryRules.MAX_COFFINS <= 8, "six to eight coffins");

        System.out.println("OssuaryRulesTest passed");
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int n : a) {
            s += n;
        }
        return s;
    }

    private static int count(int[] a, int v) {
        int s = 0;
        for (int n : a) {
            if (n == v) {
                s++;
            }
        }
        return s;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
