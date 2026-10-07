package pocketdungeons;

import java.util.List;

/**
 * What an interval banks when it ends, per member: with J1 the floors already
 * paid their scrap at each clear and going home converts nothing, so the
 * settlement is down to the chest count and the journal row. No Minecraft
 * imports, the same discipline as {@link KeystoneMath} and {@link Omen}: the
 * arithmetic is the part that is easy to get wrong and trivial to test with
 * plain {@code javac}.
 *
 * <p>The checkpoint exit used to settle one band worse; D24 drops that: a
 * checkpoint exit, a grace expiry and the home lever settle identically, and
 * only the journal's {@code trigger} tells them apart. Omen still matters in
 * play (J3 reads it as lives); it just never touches what a trip pays.
 */
final class IntervalBanking {

    private IntervalBanking() {}

    /** How much chart scrap makes one chart. */
    static final int SCRAP_PER_CHART = 5;

    /**
     * One member's settlement.
     *
     * @param levels    compass levels gained: whole charts brought home
     * @param scrapLeft the scrap below a whole chart, lost on the way out
     * @param chests    the chest count the trip's loot roll pays
     */
    record Settlement(int levels, int scrapLeft, int chests) {}

    /** The dealt scrap a list of cleared floors adds up to, before any level discount. */
    static int stepSum(List<Integer> floorSteps) {
        int sum = 0;
        for (int step : floorSteps) {
            sum += Math.max(0, step);
        }
        return sum;
    }

    /**
     * The scrap one floor actually pays a member whose compass is
     * {@code memberLevel}: the dealt scrap minus how far the member stands
     * above the floor's level, floored at 0. Superseded at the floor clear by
     * {@link FloorPay}; kept for the go-home screen's "what would a chart be"
     * read.
     */
    static int effectiveScrap(int dealt, int floorLevel, int memberLevel) {
        return Math.max(0, Math.max(0, dealt) - Math.max(0, memberLevel - floorLevel));
    }

    /** The scrap a member's cleared floors total: the effective scrap of each. */
    static int effectiveScrap(List<Integer> floorSteps, List<Integer> floorLevels, int memberLevel) {
        int scrap = 0;
        for (int i = 0; i < floorSteps.size(); i++) {
            int floorLevel = i < floorLevels.size() ? floorLevels.get(i) : memberLevel;
            scrap += effectiveScrap(floorSteps.get(i), floorLevel, memberLevel);
        }
        return scrap;
    }

    /** The whole charts {@code scrap} converts to. */
    static int chartsOf(int scrap) {
        return Math.max(0, scrap) / SCRAP_PER_CHART;
    }

    /**
     * Settles an interval for one member. Identical for the home lever, a
     * checkpoint exit and a grace expiry (D24): the caller's {@code trigger}
     * is the only difference, and it is for the journal only.
     *
     * @param floorSteps  each cleared floor's dealt chart scrap, in order
     * @param floorLevels each cleared floor's level, in order (shorter lists
     *                    treat a floor as the member's own level)
     * @param memberLevel the member's compass level, for the scrap read
     * @param bonusChests the zone's depth bonus for this many floors
     */
    static Settlement settle(List<Integer> floorSteps, List<Integer> floorLevels,
                             int memberLevel, int bonusChests) {
        int chests = Omen.baseRewardChests() + Math.max(0, bonusChests);
        if (floorSteps.isEmpty()) {
            return new Settlement(0, 0, chests);
        }
        int scrap = effectiveScrap(floorSteps, floorLevels, memberLevel);
        return new Settlement(chartsOf(scrap), scrap % SCRAP_PER_CHART, chests);
    }

    // ---- words (pure strings; callers wrap them in components) ---------------

    /** {@code "2 chests"} or {@code "1 chest"}. */
    static String chests(int chests) {
        return chests + (chests == 1 ? " chest" : " chests");
    }

    /** {@code "3 scrap"}: the boards' scrap part ("scrap" is a mass noun). */
    static String scrapText(int scrap) {
        return scrap + " scrap";
    }

    /** {@code "2 charts"} or {@code "1 chart"}. */
    static String chartText(int charts) {
        return charts + (charts == 1 ? " chart" : " charts");
    }

    /** How a part of the go-home board's outcome line is coloured. */
    enum HomeTone {
        /** The scrap total: cyan, like everything you take home. */
        SCRAP,
        /** The charts it makes: green. */
        CHARTS,
        /** What is still missing for a chart: gold. */
        NEEDS,
        /** What would be lost on the way out, or nothing carried: gray. */
        LOST
    }

    /** One coloured piece of the outcome line. */
    record HomePart(String text, HomeTone tone) {}

    /**
     * The go-home board: always {@code GO HOME} (gold, or green once
     * {@code finished}, the moment the dungeon's final floor is cleared), the
     * scrap carried, and what it comes to. Structured rather than a newline
     * string so the screen colours each part without splitting text.
     *
     * @param scrapLine {@code "7 scrap"}, or empty when nothing is carried
     * @param outcome   the pieces of the second line, joined by {@code " \u00b7 "}
     */
    record HomeScreen(String title, boolean finished, String scrapLine, List<HomePart> outcome) {

        /** The board as plain text: the scrap line (when any), then the outcome line. */
        String body() {
            StringBuilder out = new StringBuilder();
            if (!scrapLine.isEmpty()) {
                out.append(scrapLine).append('\n');
            }
            for (int i = 0; i < outcome.size(); i++) {
                out.append(i == 0 ? "" : " \u00b7 ").append(outcome.get(i).text());
            }
            return out.toString();
        }
    }

    /** The scrap a settlement carries: its whole charts at {@link #SCRAP_PER_CHART} each, plus the remainder. */
    static int carried(Settlement s) {
        return s.levels() * SCRAP_PER_CHART + s.scrapLeft();
    }

    /**
     * The go-home board for what the lever would carry home right now.
     * Carried 0 reads {@code no scrap yet}; 3 reads {@code 3 scrap} over
     * {@code 2 more for a chart}; 5 reads {@code 1 chart}; 7 reads
     * {@code 1 chart \u00b7 2 scrap lost}; 10 reads {@code 2 charts}.
     * (Step 16 rewrites this board to the J1 wording; until then it still
     * answers "how far to the next chart".)
     */
    static HomeScreen homeScreen(Settlement now, boolean finished) {
        int carried = carried(now);
        List<HomePart> outcome = new java.util.ArrayList<>();
        if (carried <= 0) {
            outcome.add(new HomePart("no scrap yet", HomeTone.LOST));
            return new HomeScreen("GO HOME", finished, "", outcome);
        }
        if (now.levels() > 0) {
            outcome.add(new HomePart(chartText(now.levels()), HomeTone.CHARTS));
            if (now.scrapLeft() > 0) {
                outcome.add(new HomePart(now.scrapLeft() + " scrap lost", HomeTone.LOST));
            }
        } else {
            outcome.add(new HomePart((SCRAP_PER_CHART - now.scrapLeft()) + " more for a chart", HomeTone.NEEDS));
        }
        return new HomeScreen("GO HOME", finished, scrapText(carried), outcome);
    }
}
