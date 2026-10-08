package pocketdungeons;

/**
 * The haul ledger's arithmetic ("Haul and Blood Doors", decision of 2026-10-07).
 * Three numbers live on a member's log entry:
 * <ul>
 *   <li>the <b>compass</b> ({@code highestCharts}): the permanent level, which only ever rises;</li>
 *   <li>the <b>chart progress</b>: banked scrap toward the next level, 0 to 4;</li>
 *   <li>the <b>haul</b>: scrap carried on the current trip, at risk until it is banked.</li>
 * </ul>
 * Floors pay into the haul. Going home or finishing banks it (100 percent); a failed dungeon
 * banks {@link #FAIL_KEEP_PERCENT} of it and loses the rest. Scrap is never spent: side doors
 * cost a life ({@link DoorLives}), so an overleveled player can always pay.
 *
 * <p>No Minecraft imports, so {@code ScrapMathTest} runs with plain {@code javac}.
 */
final class ScrapMath {

    private ScrapMath() {}

    /** How much banked scrap makes one chart (one compass level). */
    static final int SCRAP_PER_CHART = 5;

    /** The share of a haul a member keeps when the dungeon fails, as a percent. A config knob. */
    static final int FAIL_KEEP_PERCENT = 50;

    /**
     * What a cleared floor pays into one member's haul. A floor at or above the member's compass
     * pays its dealt step. A floor below it pays 1 whatever the gap (never nothing, never the
     * full step), so a strong player in easy content still earns, at about half the rate of
     * content at their level. A floor dealt no step pays nothing.
     */
    static int floorPay(int step, int floorLevel, int compass) {
        int dealt = Math.max(0, step);
        if (dealt == 0) {
            return 0;
        }
        return floorLevel >= compass ? dealt : 1;
    }

    /** The result of banking: the new compass, the new bar fill, and the levels gained. */
    record Banked(int compass, int progress, int gained) {}

    /** Banks {@code haul} scrap into a compass of {@code compass} with {@code progress} already in the bar. */
    static Banked bank(int compass, int progress, int haul) {
        int total = Math.max(0, progress) + Math.max(0, haul);
        int gained = total / SCRAP_PER_CHART;
        return new Banked(Math.max(0, compass) + gained, total % SCRAP_PER_CHART, gained);
    }

    /** The part of a haul a failed dungeon still banks: {@code keepPercent} percent, rounded down. */
    static int keptOnFail(int haul, int keepPercent) {
        int pct = Math.max(0, Math.min(100, keepPercent));
        return Math.max(0, haul) * pct / 100;
    }

    /**
     * Migration from the pool model: the bar starts at what the old spendable pool held past the
     * compass's own whole charts, clamped to the bar. A never-spent new player with pool 16 at
     * compass 3 keeps 1/5; a migrated or spent-down player lands on 0/5.
     */
    static int migratedProgress(int oldScrapPool, int compass) {
        int past = Math.max(0, oldScrapPool) - SCRAP_PER_CHART * Math.max(0, compass);
        return Math.max(0, Math.min(SCRAP_PER_CHART - 1, past));
    }
}
