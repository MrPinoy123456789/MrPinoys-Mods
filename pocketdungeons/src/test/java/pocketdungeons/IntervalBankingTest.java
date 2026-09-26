package pocketdungeons;

import java.util.List;

/**
 * Pure-JDK regression for per-floor banking ({@link IntervalBanking}, audit
 * B4): the average-of-doors level gain, remainders carried across intervals,
 * the band scaled to the floors actually cleared, the dire band banking
 * nothing and keeping the carry, the checkpoint exit banking one band worse,
 * and the words on the door and HOME screens.
 */
public class IntervalBankingTest {

    private static final int L = 3;

    public static void main(String[] args) {
        testSamePaceAsTheForcedSafeRoom();
        testRemaindersCarryAcrossIntervals();
        testBandScalesWithFloorsCleared();
        testDireBandKeepsTheCarry();
        testLeavingBanksOneBandWorse();
        testChestsCarryTheDepthBonus();
        testPartyMembersBankFromTheirOwnCarry();
        testIntervalLengthShortened();
        testWords();
        System.out.println("IntervalBankingTest passed");
    }

    /** Three Greater floors bank +3, three free floors +1: today's pace. */
    private static void testSamePaceAsTheForcedSafeRoom() {
        expect(settle(List.of(3, 3, 3), 0, 0, 0), 0, 3, 0);
        expect(settle(List.of(1, 1, 1), 0, 0, 0), 0, 1, 0);
        expect(settle(List.of(2, 2, 2), 0, 0, 0), 0, 2, 0);
        // The last door no longer decides: free, free, Greater banks 1 and keeps 2.
        expect(settle(List.of(1, 1, 3), 0, 0, 0), 0, 1, 2);
        // Six Greater floors in one interval bank six levels, one a floor, as
        // two intervals of three would have.
        expect(settle(List.of(3, 3, 3, 3, 3, 3), 0, 0, 0), 0, 6, 0);
    }

    /** Banking early loses nothing: the remainder waits for the next interval. */
    private static void testRemaindersCarryAcrossIntervals() {
        // One free floor, bank: nothing yet, 1 of 3 kept.
        IntervalBanking.Settlement first = settle(List.of(1), 0, 0, 0);
        expect(first, 0, 0, 1);
        // Another free floor, bank: still nothing, 2 kept.
        IntervalBanking.Settlement second = settle(List.of(1), 0, first.progress(), 0);
        expect(second, 0, 0, 2);
        // A third: the level lands, as three floors in one interval would have.
        IntervalBanking.Settlement third = settle(List.of(1), 0, second.progress(), 0);
        expect(third, 0, 1, 0);
        // One Greater floor banks a level on its own.
        expect(settle(List.of(3), 0, 0, 0), 0, 1, 0);
        // A carry of 2 and a +2 floor make 4: one level, 1 kept.
        expect(settle(List.of(2), 0, 2, 0), 0, 1, 1);
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
    }

    /** A dire interval banks no levels and neither spends nor adds to the carry. */
    private static void testDireBandKeepsTheCarry() {
        expect(settle(List.of(3, 3, 3), 12, 2, 0), 2, 0, 2);
        expect(settle(List.of(1), 4, 0, 0), 2, 0, 0);
    }

    /** Leaving at a checkpoint settles one band worse, never worse than dire. */
    private static void testLeavingBanksOneBandWorse() {
        int penalty = IntervalBanking.LEAVE_PENALTY;
        // Calm becomes uneasy: the levels still bank, one chest fewer.
        IntervalBanking.Settlement calm = settle(List.of(3, 3, 3), 3, 0, penalty);
        expect(calm, 1, 3, 0);
        check(calm.chests() == 2, "uneasy pays two chests");
        // Uneasy becomes dire: nothing banks, the carry is kept.
        expect(settle(List.of(3, 3, 3), 9, 1, penalty), 2, 0, 1);
        // Dire stays dire.
        expect(settle(List.of(3), 4, 0, penalty), 2, 0, 0);
    }

    private static void testChestsCarryTheDepthBonus() {
        check(settle(List.of(1, 1, 1), 0, 0, 0).chests() == 3, "calm, no bonus: 3");
        IntervalBanking.Settlement deep = IntervalBanking.settle(List.of(1, 1, 1, 1), 5, 0, L, 0,
                ZoneRules.DEFAULT.bonusChests(4));
        check(deep.band() == 1 && deep.chests() == 3, "uneasy at floor 4: 2 plus 1 bonus");
    }

    /** The interval is shared; each member's carry is their own. */
    private static void testPartyMembersBankFromTheirOwnCarry() {
        List<Integer> steps = List.of(2, 3);
        IntervalBanking.Settlement owner = settle(steps, 0, 0, 0);
        IntervalBanking.Settlement rider = settle(steps, 0, 2, 0);
        expect(owner, 0, 1, 2);
        expect(rider, 0, 2, 1);
    }

    /** A carry above a shortened interval is not shaved; the next bank spends it. */
    private static void testIntervalLengthShortened() {
        expect(IntervalBanking.settle(List.of(1), 0, 4, 3, 0, 0), 0, 1, 2);
        // A dire bank leaves even that surplus alone.
        expect(IntervalBanking.settle(List.of(1), 4, 4, 3, 0, 0), 2, 0, 4);
    }

    private static void testWords() {
        checkEquals(IntervalBanking.doorLine(3, 4), "+3 toward your key, +4 so far");
        checkEquals(IntervalBanking.levels(1), "+1 level");
        checkEquals(IntervalBanking.levels(2), "+2 levels");
        checkEquals(IntervalBanking.chests(1), "1 chest");
        checkEquals(IntervalBanking.homeScreen(settle(List.of(3, 1), 1, 0, 0), L, false),
                "HOME\nBanks +1 level, 1/3 kept\n3 chests, calm");
        checkEquals(IntervalBanking.homeScreen(settle(List.of(3, 3, 3), 12, 0, 0), L, true),
                "TIME TO GO HOME\nBanks no levels\n1 chest, dire");
        checkEquals(IntervalBanking.bankedLine(settle(List.of(3, 3, 3), 0, 0, 0), L, false),
                "The interval banks: +3 levels, 0 of 3 toward the next, 3 chests (calm).");
        String left = IntervalBanking.bankedLine(settle(List.of(3), 0, 0, IntervalBanking.LEAVE_PENALTY), L, true);
        check(left.startsWith("You leave at the checkpoint, and the interval banks one band worse:"), left);
        for (String line : List.of(left, IntervalBanking.doorLine(1, 0))) {
            check(!line.contains("--") && !line.contains("\u2014"), "no dash punctuation: " + line);
        }
    }

    private static IntervalBanking.Settlement settle(List<Integer> steps, int omen, int carried, int penalty) {
        return IntervalBanking.settle(steps, omen, carried, L, penalty, 0);
    }

    private static void expect(IntervalBanking.Settlement actual, int band, int levels, int progress) {
        if (actual.band() != band || actual.levels() != levels || actual.progress() != progress) {
            throw new AssertionError("expected band " + band + ", +" + levels + ", " + progress
                    + " kept, but was " + actual);
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
