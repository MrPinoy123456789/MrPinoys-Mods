package pocketdungeons;

/** Pure checks of the lost and found arithmetic (the item merging needs a game and is in WildClearGameTest). */
public final class WildSweepTest {

    private WildSweepTest() {}

    public static void main(String[] args) {
        eq(0, WildSweep.plan(0, 64).chests(), "nothing to sweep needs no chest");
        eq(1, WildSweep.plan(1, 64).chests(), "one stack needs one chest");
        eq(1, WildSweep.plan(27, 64).chests(), "a full chest is one chest");
        eq(2, WildSweep.plan(28, 64).chests(), "one stack over spills into a second chest");
        eq(0, WildSweep.plan(28, 64).lostStacks(), "nothing is lost under the cap");
        eq(2, WildSweep.plan(500, 2).chests(), "the cap limits the chests");
        eq(500 - 54, WildSweep.plan(500, 2).lostStacks(), "what does not fit is counted as lost");
        eq(5, WildSweep.plan(5, 0).lostStacks(), "a zero cap loses everything");
        System.out.println("WildSweepTest passed");
    }

    private static void eq(int expected, int actual, String what) {
        if (expected != actual) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
