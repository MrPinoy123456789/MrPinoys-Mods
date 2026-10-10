package pocketdungeons;

/**
 * The haul ledger's arithmetic ("Haul and Blood Doors", decision of 2026-10-07).
 * Three numbers live on a member's log entry:
 * <ul>
 *   <li>the <b>compass</b> ({@code highestCharts}): the permanent level, which only ever rises;</li>
 *   <li>the <b>chart progress</b>: banked scrap toward the next level (the cost rises with the compass, see {@link #levelCost});</li>
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

    /** The flat price of a level under the pool and the first haul model; only the migration reads it. */
    static final int LEGACY_SCRAP_PER_CHART = 5;

    /**
     * What it costs to go from compass {@code compass} to the next level (design pass 2026-10-09, Q2): a base
     * plus one more for every {@code every} levels, so a level asks a little more as the compass climbs.
     * With the defaults (4 and 3): compass 1 costs 4, 5 costs 5, 10 costs 7, 25 costs 12, 50 costs 20.
     */
    static int levelCost(int compass, int base, int every) {
        return Math.max(1, base) + Math.max(0, compass) / Math.max(1, every);
    }

    /** {@link #levelCost(int, int, int)} with the configured knobs. */
    static int levelCost(int compass) {
        return levelCost(compass, PocketDungeonsConfig.scrapCostBase(), PocketDungeonsConfig.scrapCostEvery());
    }

    /** All the scrap a compass of {@code compass} with {@code progress} in its bar represents, for comparing. */
    static int totalScrap(int compass, int progress) {
        int total = Math.max(0, progress);
        for (int c = 0; c < Math.max(0, compass); c++) {
            total += levelCost(c);
        }
        return total;
    }

    /** The bonus a floor of act {@code act} pays on top of its step: {@code perAct} for every act above the first. */
    static int actBonus(int act, int perAct) {
        return Math.max(0, act - 1) * Math.max(0, perAct);
    }

    /** The bonus an Endless Mine floor pays for its depth, in place of the act bonus. */
    static int endlessBonus(int floorNumber, int every, int max) {
        return Math.min(Math.max(0, max), Math.max(0, floorNumber) / Math.max(1, every));
    }

    /** The share of a haul a member keeps when the dungeon fails, as a percent. A config knob. */
    static final int FAIL_KEEP_PERCENT = 50;

    /**
     * What a cleared floor pays into one member's haul. A floor at or above the member's compass
     * pays its dealt step. A floor below it pays 1 whatever the gap (never nothing, never the
     * full step), so a strong player in easy content still earns, at about half the rate of
     * content at their level. A floor dealt no step pays nothing.
     */
    static int floorPay(int step, int floorLevel, int compass) {
        return floorPay(step, floorLevel, compass, 0);
    }

    /**
     * {@link #floorPay(int, int, int)} with a {@code bonus} on top of the dealt step (the act and the final
     * floor, see {@link #actBonus} and {@link PocketDungeonsConfig#scrapFinalBonus}). At or above the compass
     * the floor pays step plus bonus; below it, {@code belowCompassPercent} of that, rounded down, never less
     * than 1. A floor dealt no step still pays nothing.
     */
    static int floorPay(int step, int floorLevel, int compass, int bonus) {
        return floorPay(step, floorLevel, compass, bonus, PocketDungeonsConfig.belowCompassPercent());
    }

    static int floorPay(int step, int floorLevel, int compass, int bonus, int belowPercent) {
        int dealt = Math.max(0, step);
        if (dealt == 0) {
            return 0;
        }
        int full = dealt + Math.max(0, bonus);
        if (floorLevel >= compass) {
            return full;
        }
        int pct = Math.max(0, Math.min(100, belowPercent));
        return Math.max(1, full * pct / 100);
    }

    /** The result of banking: the new compass, the new bar fill, and the levels gained. */
    record Banked(int compass, int progress, int gained) {}

    /**
     * Banks {@code haul} scrap into a compass of {@code compass} with {@code progress} already in the bar.
     * Each level costs {@link #levelCost} at the level it leaves, so a big haul crosses several rising prices.
     */
    static Banked bank(int compass, int progress, int haul) {
        return bank(compass, progress, haul, PocketDungeonsConfig.scrapCostBase(), PocketDungeonsConfig.scrapCostEvery());
    }

    static Banked bank(int compass, int progress, int haul, int base, int every) {
        int level = Math.max(0, compass);
        int left = Math.max(0, progress) + Math.max(0, haul);
        int gained = 0;
        while (left >= levelCost(level, base, every)) {
            left -= levelCost(level, base, every);
            level++;
            gained++;
        }
        return new Banked(level, left, gained);
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
        int past = Math.max(0, oldScrapPool) - LEGACY_SCRAP_PER_CHART * Math.max(0, compass);
        return Math.max(0, Math.min(levelCost(compass) - 1, past));
    }
}
