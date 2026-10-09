package pocketdungeons;

/** Q4: how big a finale wave is for a party, how tough the elite is, and what the pad says. */
public class FinaleRulesTest {

    public static void main(String[] args) {
        // A party of one is the authored wave; each extra member adds the percent, rounded up.
        eq(FinaleRules.count(4, 1, 50), 4);
        eq(FinaleRules.count(4, 2, 50), 6);
        eq(FinaleRules.count(3, 2, 50), 5);   // 4.5 rounds up
        eq(FinaleRules.count(10, 4, 50), 25);
        eq(FinaleRules.count(4, 3, 0), 4);    // 0 percent: the party changes nothing
        eq(FinaleRules.count(4, 0, 50), 4);   // no party is a party of one
        eq(FinaleRules.count(0, 4, 50), 0);

        int[] one = FinaleRules.counts(new int[]{4, 3}, 1, 50);
        eq(one[0], 4);
        eq(one[1], 3);
        int[] four = FinaleRules.counts(new int[]{10, 4}, 4, 50);
        eq(four[0] + four[1], FinaleRules.MAX_WAVE - 0 > 25 + 10 ? 25 + 10 : FinaleRules.MAX_WAVE);   // 25 and 10 fit under 40
        // A wave that would pass the cap is trimmed from its biggest group, keeping the mix.
        int[] capped = FinaleRules.counts(new int[]{20, 20}, 4, 100);
        eq(capped[0] + capped[1], FinaleRules.MAX_WAVE);
        eq(capped[0], 20);
        eq(capped[1], 20);
        int[] lopsided = FinaleRules.counts(new int[]{20, 2}, 4, 100);
        eq(lopsided[0] + lopsided[1], FinaleRules.MAX_WAVE);
        check(lopsided[1] >= 8, "the small group keeps its size while the big one gives up mobs: " + lopsided[1]);

        // The elite grows with the party and is never under 1.
        eq(FinaleRules.eliteHealth(60, 1, 50), 60);
        eq(FinaleRules.eliteHealth(60, 2, 50), 90);
        eq(FinaleRules.eliteHealth(60, 4, 50), 150);
        eq(FinaleRules.eliteHealth(60, 4, 0), 60);
        eq(FinaleRules.eliteHealth(0, 1, 50), 1);

        // The pad: silent until the finale is due, then names the wait, silent again once it is won.
        check(FinaleRules.padRefusal(false, false, 0) == null, "not due: the normal gate speaks");
        check(FinaleRules.padRefusal(true, true, 0) == null, "won: the pad is open");
        check(FinaleRules.padRefusal(true, false, 0).contains("stirring"), "due, not spawned yet");
        check(FinaleRules.padRefusal(true, false, 7).contains("7 left"), "on, with the count");
        for (String line : new String[]{FinaleRules.padRefusal(true, false, 0), FinaleRules.padRefusal(true, false, 3)}) {
            check(!line.contains("--") && !line.contains("\u2014"), "no dash punctuation: " + line);
        }
        System.out.println("FinaleRulesTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
