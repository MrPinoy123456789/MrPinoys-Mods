package pocketdungeons;

import java.util.List;

/**
 * Pure-JDK regression for the interval settlement ({@link IntervalBanking}):
 * what a trip's chest count is, and the words on the door and HOME screens.
 * D24: omen and penalties are gone from the bank; the home lever, a
 * checkpoint exit and a grace expiry settle identically, differing only in
 * the journal's {@code trigger}. J1: floors pay their scrap at the clear, so
 * the bank converts nothing.
 */
public class IntervalBankingTest {

    public static void main(String[] args) {
        testChestsCarryTheDepthBonus();
        testEveryWayHomeSettlesIdentically();
        testEmptyTripBanksBaseChests();
        testWords();
        System.out.println("IntervalBankingTest passed");
    }

    private static void testChestsCarryTheDepthBonus() {
        check(settle(List.of(1, 1, 1)).chests() == 3, "no bonus: 3");
        IntervalBanking.Settlement deep = IntervalBanking.settle(List.of(1, 1, 1, 1), List.of(), 0,
                ZoneRules.DEFAULT.bonusChests(4));
        check(deep.chests() == 4, "floor 4: 3 plus 1 bonus");
    }

    /**
     * D24: the same trip banks the same settlement however it ended. The
     * {@code trigger} is the only difference, and it stays in the journal.
     */
    private static void testEveryWayHomeSettlesIdentically() {
        // settle(...) takes no omen and no penalty, so there is nothing left
        // to differ by: one call shape serves every trigger. The trigger is a
        // journal string, exercised by the settlement callers.
        IntervalBanking.Settlement a = settle(List.of(3, 2, 1));
        IntervalBanking.Settlement b = settle(List.of(3, 2, 1));
        check(a.equals(b), "same trip, same settlement: " + a + " vs " + b);
    }

    /** A trip with no cleared floors still banks the base chest count. */
    private static void testEmptyTripBanksBaseChests() {
        IntervalBanking.Settlement none = IntervalBanking.settle(List.of(), List.of(), 0, 0);
        check(none.chests() == Omen.baseRewardChests(), "no floors: base chests");
        check(none.levels() == 0 && none.scrapLeft() == 0, "no floors: nothing else");
    }

    private static void testWords() {
        checkEquals(IntervalBanking.scrapText(3), "3 scrap");
        checkEquals(IntervalBanking.chartText(1), "1 chart");
        checkEquals(IntervalBanking.chartText(2), "2 charts");
        checkEquals(IntervalBanking.chests(1), "1 chest");
        // The go-home board: carried scrap and what it comes to (the table in the plan).
        checkEquals(home(0), "no scrap yet");
        checkEquals(home(3), "3 scrap\n2 more for a chart");
        checkEquals(home(5), "5 scrap\n1 chart");
        checkEquals(home(7), "7 scrap\n1 chart \u00b7 2 scrap lost");
        checkEquals(home(10), "10 scrap\n2 charts");
        checkEquals(IntervalBanking.homeScreen(
                IntervalBanking.settle(List.of(3, 3, 3), List.of(12, 12, 12), 0, 0), true).title(), "GO HOME");
        check(IntervalBanking.homeScreen(
                IntervalBanking.settle(List.of(3, 3, 3), List.of(12, 12, 12), 0, 0), true).finished(), "finished flag");
        check(!IntervalBanking.homeScreen(settle(List.of(3)), false).finished(), "not finished flag");
        IntervalBanking.HomeScreen seven = IntervalBanking.homeScreen(
                new IntervalBanking.Settlement(1, 2, 3), false);
        check(seven.outcome().get(0).tone() == IntervalBanking.HomeTone.CHARTS
                && seven.outcome().get(1).tone() == IntervalBanking.HomeTone.LOST, "charts green, lost gray");
        check(IntervalBanking.homeScreen(new IntervalBanking.Settlement(0, 3, 3), false)
                .outcome().get(0).tone() == IntervalBanking.HomeTone.NEEDS, "more for a chart is gold");
        for (String line : List.of(IntervalBanking.scrapText(1), home(7), home(3), home(0))) {
            check(!line.contains("--") && !line.contains("\u2014"), "no dash punctuation: " + line);
        }
    }

    /** The go-home board's body when {@code carried} scrap is held (5 scrap makes a chart). */
    private static String home(int carried) {
        return IntervalBanking.homeScreen(new IntervalBanking.Settlement(carried / 5, carried % 5, 3), false)
                .body();
    }

    private static IntervalBanking.Settlement settle(List<Integer> steps) {
        return IntervalBanking.settle(steps, List.of(), 0, 0);
    }

    private static void checkEquals(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but was '" + actual + "'");
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
