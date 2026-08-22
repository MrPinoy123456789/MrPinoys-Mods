package pocketdungeons;

public class PayoutMathTest {

    public static void main(String[] args) {
        testStreakTransitions();
        testStreakEdgeCases();
        testPayoutCurve();
        testBonusCap();
        System.out.println("PayoutMathTest passed");
    }

    private static void testStreakTransitions() {
        // First ever completion.
        check(PayoutMath.nextStreak(null, "2026-08-21", 0), 1);
        check(PayoutMath.nextStreak("", "2026-08-21", 0), 1);
        // Same day: unchanged.
        check(PayoutMath.nextStreak("2026-08-21", "2026-08-21", 3), 3);
        // Exactly one day later: up one.
        check(PayoutMath.nextStreak("2026-08-20", "2026-08-21", 3), 4);
        // A larger gap starts over.
        check(PayoutMath.nextStreak("2026-08-17", "2026-08-21", 9), 1);
        // Across a month boundary the day count, not the date string, decides.
        check(PayoutMath.nextStreak("2026-07-31", "2026-08-01", 2), 3);
    }

    private static void testStreakEdgeCases() {
        // A clock that went backwards is a gap, not a bonus.
        check(PayoutMath.nextStreak("2026-08-21", "2026-08-20", 5), 1);
        // A corrupt key resets rather than throwing.
        check(PayoutMath.nextStreak("not-a-date", "2026-08-21", 5), 1);
        // No today key at all: leave the streak where it was, floored at 1.
        check(PayoutMath.nextStreak("2026-08-20", null, 4), 4);
        check(PayoutMath.nextStreak("2026-08-20", null, 0), 1);
        // Same day with a zeroed streak still reads as at least one day in.
        check(PayoutMath.nextStreak("2026-08-21", "2026-08-21", 0), 1);
    }

    private static void testPayoutCurve() {
        // The plan's own worked examples, at the shipped defaults.
        check(PayoutMath.count(6, 4, 1, 1, 10, 100), 6);
        // U5 Stage 3's prose says a tier-3 run on a 10-day streak pays 28, but
        // its own formula -- streakBonusPercent * (streak - 1) -- makes day ten
        // a 90% bonus, not 100%: 14 * 1.90 = 26.6, floored to 26. The formula is
        // the normative half, so 26 is the shipped number and the prose is the
        // part that drifted. Day eleven is where the cap is reached and 28 lands.
        check(PayoutMath.count(6, 4, 3, 10, 10, 100), 26);
        check(PayoutMath.count(6, 4, 3, 11, 10, 100), 28);
        // Tier steps by perTier before any streak applies.
        check(PayoutMath.count(6, 4, 2, 1, 10, 100), 10);
        check(PayoutMath.count(6, 4, 3, 1, 10, 100), 14);
        // Tier 0 or negative cannot happen from a live layout, but must not
        // subtract from the base if it ever did.
        check(PayoutMath.count(6, 4, 0, 1, 10, 100), 6);
    }

    private static void testBonusCap() {
        // 10%/day, capped at 100%: day 11 and day 500 pay the same.
        check(PayoutMath.count(6, 4, 1, 11, 10, 100), 12);
        check(PayoutMath.count(6, 4, 1, 500, 10, 100), 12);
        check(PayoutMath.streakBonusPercent(1, 10, 100), 0);
        check(PayoutMath.streakBonusPercent(4, 10, 100), 30);
        check(PayoutMath.streakBonusPercent(50, 10, 100), 100);
        // A zero base pays nothing however long the streak.
        check(PayoutMath.count(0, 0, 3, 30, 10, 100), 0);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
