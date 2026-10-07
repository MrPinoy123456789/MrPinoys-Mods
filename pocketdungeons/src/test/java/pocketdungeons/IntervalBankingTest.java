package pocketdungeons;

import java.util.List;

/**
 * Pure-JDK regression for chart scrap banking ({@link IntervalBanking},
 * 2026-10-05 rework): floors deal 1 to 3 scrap, five scrap make a chart and a
 * chart is a compass level, leftover scrap is lost (nothing carries between
 * trips), a floor beneath the member's compass pays discounted or no scrap,
 * the band scales to the floors cleared, a checkpoint exit banks one band
 * worse, and the words on the door and HOME screens.
 */
public class IntervalBankingTest {

    public static void main(String[] args) {
        testChartsAtFiveScrap();
        testLeftoverScrapIsLost();
        testFloorBelowCompassPaysLess();
        testBandScalesWithFloorsCleared();
        testLeavingBanksOneBandWorse();
        testChestsCarryTheDepthBonus();
        testPartyMembersBankAtTheirOwnLevel();
        testWords();
        System.out.println("IntervalBankingTest passed");
    }

    /** Five scrap is a chart, a chart is a level: the new pace. */
    private static void testChartsAtFiveScrap() {
        // Three +3 floors: 9 scrap, one chart home, 4 left behind.
        expect(settle(List.of(3, 3, 3), 0, 0, 0), 0, 1, 4);
        expect(settle(List.of(1, 1, 1), 0, 0, 0), 0, 0, 3);
        expect(settle(List.of(2, 2, 2), 0, 0, 0), 0, 1, 1);
        // Six +3 floors: 18 scrap, three charts.
        expect(settle(List.of(3, 3, 3, 3, 3, 3), 0, 0, 0), 0, 3, 3);
        // A full 5 lands exactly.
        expect(settle(List.of(3, 2), 0, 0, 0), 0, 1, 0);
    }

    /** Scrap under a chart is pulp: the next trip starts from zero. */
    private static void testLeftoverScrapIsLost() {
        IntervalBanking.Settlement first = settle(List.of(1), 0, 0, 0);
        expect(first, 0, 0, 1);
        // A later interval settles the same way: nothing was kept to carry.
        expect(settle(List.of(1), 0, 0, 0), 0, 0, 1);
    }

    /** The owner's rule: scrap minus how far the member stands above the floor. */
    private static void testFloorBelowCompassPaysLess() {
        check(IntervalBanking.effectiveScrap(3, 10, 12) == 1, "3 dealt, 2 above: 1 paid");
        check(IntervalBanking.effectiveScrap(2, 10, 20) == 0, "far beneath: nothing");
        check(IntervalBanking.effectiveScrap(2, 10, 10) == 2, "at the floor's level: full");
        check(IntervalBanking.effectiveScrap(2, 12, 10) == 2, "above the member: full");
        // Per member at settlement: the owner earns, a higher-level rider does not.
        List<Integer> steps = List.of(3);
        List<Integer> levels = List.of(10);
        expect(IntervalBanking.settle(steps, levels, 0, 10, 0, 0), 0, 0, 3);
        expect(IntervalBanking.settle(steps, levels, 0, 14, 0, 0), 0, 0, 0);
        // A floor list shorter than the steps defaults to the member's level.
        expect(IntervalBanking.settle(steps, List.of(), 0, 14, 0, 0), 0, 0, 3);
    }

    /** One calm floor is as calm as three: the band thresholds follow the floors cleared. */
    private static void testBandScalesWithFloorsCleared() {
        check(IntervalBanking.band(1, 1, 0) == 0, "1 omen over 1 floor is calm");
        check(IntervalBanking.band(2, 1, 0) == 1, "2 omen over 1 floor is uneasy");
        check(IntervalBanking.band(4, 1, 0) == 2, "4 omen over 1 floor is dire");
        check(IntervalBanking.band(4, 2, 0) == 1, "4 omen over 2 floors is uneasy");
        check(IntervalBanking.band(3, 3, 0) == 0, "3 omen over 3 floors is calm");
        check(IntervalBanking.band(12, 4, 0) == 1, "12 omen over 4 floors is uneasy");
        check(IntervalBanking.band(13, 4, 0) == 2, "13 omen over 4 floors is dire");
        check(IntervalBanking.band(0, 0, 0) == 0, "no floors reads as one");
        // Dire still banks charts: omen adds danger, not reward cuts.
        expect(settle(List.of(3, 3, 3, 3, 3), 20, 0, 0), 2, 3, 0);
    }

    /** Leaving at a checkpoint settles one band worse, never worse than dire. */
    private static void testLeavingBanksOneBandWorse() {
        int penalty = IntervalBanking.LEAVE_PENALTY;
        IntervalBanking.Settlement calm = settle(List.of(3, 3, 3, 3, 3), 3, 0, penalty);
        expect(calm, 1, 3, 0);
        check(calm.chests() == 3, "uneasy pays three chests");
        expect(settle(List.of(3), 4, 0, penalty), 2, 0, 3);
    }

    private static void testChestsCarryTheDepthBonus() {
        check(settle(List.of(1, 1, 1), 0, 0, 0).chests() == 3, "calm, no bonus: 3");
        IntervalBanking.Settlement deep = IntervalBanking.settle(List.of(1, 1, 1, 1), List.of(), 5, 0, 0,
                ZoneRules.DEFAULT.bonusChests(4));
        check(deep.band() == 1 && deep.chests() == 4, "uneasy at floor 4: 3 plus 1 bonus");
    }

    /** The trip is shared; each member's discount is their own. */
    private static void testPartyMembersBankAtTheirOwnLevel() {
        List<Integer> steps = List.of(3, 3);
        List<Integer> levels = List.of(9, 10);
        // The owner (level 9) earns full scrap; a rider at 12 earns 3-0 + 3-2.
        expect(IntervalBanking.settle(steps, levels, 0, 9, 0, 0), 0, 1, 1);
        expect(IntervalBanking.settle(steps, levels, 0, 12, 0, 0), 0, 0, 1);
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
        checkEquals(IntervalBanking.homeScreen(settle(List.of(3, 3, 3), 12, 0, 0), true).title(), "GO HOME");
        check(IntervalBanking.homeScreen(settle(List.of(3, 3, 3), 12, 0, 0), true).finished(), "finished flag");
        check(!IntervalBanking.homeScreen(settle(List.of(3), 0, 0, 0), false).finished(), "not finished flag");
        IntervalBanking.HomeScreen seven = IntervalBanking.homeScreen(
                new IntervalBanking.Settlement(0, 1, 2, 3), false);
        check(seven.outcome().get(0).tone() == IntervalBanking.HomeTone.CHARTS
                && seven.outcome().get(1).tone() == IntervalBanking.HomeTone.LOST, "charts green, lost gray");
        check(IntervalBanking.homeScreen(new IntervalBanking.Settlement(0, 0, 3, 3), false)
                .outcome().get(0).tone() == IntervalBanking.HomeTone.NEEDS, "more for a chart is gold");
        checkEquals(IntervalBanking.bankedLine(settle(List.of(3, 3, 3), 0, 0, 0), false),
                "Home. The compass reads 1 chart: +1 level. 4 scrap lost."
                        + " The omen was calm.");
        String left = IntervalBanking.bankedLine(
                settle(List.of(3), 0, 0, IntervalBanking.LEAVE_PENALTY), true);
        check(left.startsWith("You leave at the checkpoint, so the omen counts one band worse."), left);
        for (String line : List.of(left, IntervalBanking.scrapText(1), home(7), home(3), home(0))) {
            check(!line.contains("--") && !line.contains("\u2014"), "no dash punctuation: " + line);
        }
    }

    /** The go-home board's body when {@code carried} scrap is held (5 scrap makes a chart). */
    private static String home(int carried) {
        return IntervalBanking.homeScreen(new IntervalBanking.Settlement(0, carried / 5, carried % 5, 3), false)
                .body();
    }

    private static IntervalBanking.Settlement settle(List<Integer> steps, int omen, int memberLevel,
                                                     int penalty) {
        return IntervalBanking.settle(steps, List.of(), omen, memberLevel, penalty, 0);
    }

    private static void expect(IntervalBanking.Settlement actual, int band, int levels, int scrapLeft) {
        if (actual.band() != band || actual.levels() != levels || actual.scrapLeft() != scrapLeft) {
            throw new AssertionError("expected band " + band + ", +" + levels + ", " + scrapLeft
                    + " scrap left, but was " + actual);
        }
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
