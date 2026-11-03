package fortune.core;

import java.util.Optional;

/**
 * The calendar and the streak.
 *
 * <ul>
 *   <li>The week advances per claim, not per date: miss Tuesday and Wednesday is still day 2. Day 7 is the Golden
 *       Spin, then the strip starts again.</li>
 *   <li>The streak counts claims and survives a gap (day difference between claims) of 1 to {@code streakGraceDays};
 *       a longer gap starts it again at 1. Same rule as the old dailyquests.</li>
 *   <li>A claim {@code comebackDays} or more after the last one is a Welcome Back spin.</li>
 *   <li>Floors: days 1 and 2 pay any line, days 3 to 6 at least a pair, day 7 and Welcome Back at least three of a
 *       kind (their own tables).</li>
 * </ul>
 */
public final class Claims {

    public enum Kind { NORMAL, GOLDEN, WELCOME_BACK }

    /** What today's claim will be, decided before the roll. */
    public record Plan(long day, int weekDay, Kind kind, Line floor, int streakAfter, boolean comeback,
                       int previousBest) {
    }

    private Claims() {
    }

    /** Today's plan, or empty when today's spin is already used. */
    public static Optional<Plan> plan(PlayerRecord record, long today, Settings settings) {
        if (record.claimedOn(today)) return Optional.empty();
        boolean first = record.lastClaimDay < 0;
        long gap = first ? Long.MAX_VALUE : today - record.lastClaimDay;
        int streakAfter = !first && gap <= settings.streakGraceDays ? record.streak + 1 : 1;
        boolean comeback = !first && gap >= settings.comebackDays;
        int weekDay = record.weekPosition + 1;
        Kind kind = weekDay >= 7 ? Kind.GOLDEN : comeback ? Kind.WELCOME_BACK : Kind.NORMAL;
        Line floor = switch (kind) {
            case GOLDEN, WELCOME_BACK -> Line.FIRST_TRIPLE;
            case NORMAL -> weekDay >= 3 ? Line.PAIR : Line.NO_MATCH;
        };
        return Optional.of(new Plan(today, weekDay, kind, floor, streakAfter, comeback, record.bestStreak));
    }

    /** The reward floor shown for a future day of the week strip. */
    public static Line floorForWeekDay(int weekDay) {
        return weekDay >= 7 ? Line.FIRST_TRIPLE : weekDay >= 3 ? Line.PAIR : Line.NO_MATCH;
    }

    /**
     * Records the claim. Returns the milestone reached, or 0. The week strip's symbols are cleared when a new week's
     * first claim lands, so a finished week stays on show until then.
     */
    public static int apply(PlayerRecord record, Plan plan, String headlineSymbol, Settings settings) {
        if (record.weekPosition == 0) record.weekSymbols.clear();
        record.weekSymbols.add(headlineSymbol);
        record.weekPosition = plan.weekDay() >= 7 ? 0 : plan.weekDay();
        record.lastClaimDay = plan.day();
        record.streak = plan.streakAfter();
        record.bestStreak = Math.max(record.bestStreak, record.streak);
        record.totalClaims++;
        for (Settings.Milestone m : settings.milestones) {
            if (m.claims == record.totalClaims) return m.claims;
        }
        return 0;
    }

    /** The last day a claim keeps the streak alive. */
    public static long streakSafeUntil(PlayerRecord record, Settings settings) {
        return record.lastClaimDay + settings.streakGraceDays;
    }
}
