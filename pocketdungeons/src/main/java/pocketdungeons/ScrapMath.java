package pocketdungeons;

/**
 * The scrap ledger's arithmetic (design J1). Scrap is experience: every
 * {@link #SCRAP_PER_CHART} scrap banked on a member raises their chart level
 * one, and the compass renders that level. Two numbers live on the member's
 * log entry: {@code scrap} (the spendable pool) and {@code highestCharts}
 * (the permanent high water mark, which only ever rises).
 *
 * <p>Spending takes scrap out of the pool but never lowers
 * {@code highestCharts}, so a member who spent down to nothing still reads
 * as the level they earned.
 */
final class ScrapMath {

    private ScrapMath() {}

    /** How much scrap makes one chart. Flat for the next test; a curve is a later knob. */
    static final int SCRAP_PER_CHART = 5;

    /** The chart level a scrap pool of {@code scrap} shows. */
    static int chartLevel(int scrap) {
        return Math.max(0, scrap) / SCRAP_PER_CHART;
    }

    /** The scrap already banked past the last whole chart (the XP bar's fill). */
    static int scrapIntoChart(int scrap) {
        return Math.max(0, scrap) % SCRAP_PER_CHART;
    }

    /**
     * The permanent chart level after {@code scrap} more is earned: the new
     * pool's level, never below {@code highestCharts} already recorded.
     */
    static int highestCharts(int scrap, int earned, int highestCharts) {
        return Math.max(highestCharts, chartLevel(Math.max(0, scrap) + Math.max(0, earned)));
    }

    /** Whether a pool of {@code scrap} can pay a cost of {@code needed}. */
    static boolean canSpend(int scrap, int needed) {
        return Math.max(0, scrap) >= Math.max(0, needed);
    }

    /**
     * {@code "Not enough scrap: 4 needed, 3 carried."}: the refusal line a
     * side-branch lever reads when {@link #canSpend} fails.
     */
    static String notEnough(int needed, int carried) {
        return "Not enough scrap: " + Math.max(0, needed) + " needed, "
                + Math.max(0, carried) + " carried.";
    }
}
