package pocketdungeons;

import java.util.List;

/**
 * What an interval banks when it ends, per member: the omen band, the charts
 * carried home, and the chest count. No Minecraft imports, the same discipline
 * as {@link KeystoneMath} and {@link Omen}: the rounding and the band edges are
 * the part that is easy to get wrong and trivial to test with plain {@code javac}.
 *
 * <p>The rule, from the owner's 2026-10-05 rework of the door board: every
 * cleared floor banks its door's chart scrap (the old door step, still rolled
 * 1 to 3), five scrap make one chart, and each chart raised the compass one
 * level at settlement. Scrap is a thing you carry inside the dungeon: only
 * whole charts come home, the leftover scrap is lost. A floor that is beneath
 * the member's compass pays less or nothing
 * ({@link #effectiveScrap}): scrap minus how far their level stands above the
 * floor's, floored at 0.
 *
 * <p>Carried progress between trips is gone: an unfinished map scrap is pulp.
 * Leaving at a checkpoint instead of going home settles one band worse
 * ({@link #LEAVE_PENALTY}). The band still colours the bar.
 */
final class IntervalBanking {

    private IntervalBanking() {}

    /** Bands a checkpoint exit costs: the interval banks as if its omen were one band higher. */
    static final int LEAVE_PENALTY = 1;

    /** The highest band, the one that banks no levels. */
    static final int WORST_BAND = 2;

    /** How much chart scrap makes one chart. */
    static final int SCRAP_PER_CHART = 5;

    /**
     * One member's settlement.
     *
     * @param band    the omen band after any penalty, 0 to 2
     * @param levels  compass levels gained: whole charts brought home
     * @param scrapLeft the scrap below a whole chart, lost on the way out
     * @param chests  the chest count the band and the depth bonus pay
     */
    record Settlement(int band, int levels, int scrapLeft, int chests) {}

    /**
     * The band an interval of {@code floorsCleared} floors settles in:
     * {@link Omen#band} with thresholds scaled to the floors actually cleared,
     * made {@code penalty} bands worse and capped at the worst.
     */
    static int band(int omenSum, int floorsCleared, int penalty) {
        return Math.min(WORST_BAND, Omen.band(omenSum, Math.max(1, floorsCleared)) + Math.max(0, penalty));
    }

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
     * above the floor's level, floored at 0. A floor at or above the member
     * pays its full scrap; one far beneath pays nothing.
     */
    static int effectiveScrap(int dealt, int floorLevel, int memberLevel) {
        return Math.max(0, Math.max(0, dealt) - Math.max(0, memberLevel - floorLevel));
    }

    /** The scrap a member takes home this trip: the effective scrap of every cleared floor. */
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
     * Settles an interval for one member.
     *
     * @param floorSteps  each cleared floor's dealt chart scrap, in order
     * @param floorLevels each cleared floor's level, in order (shorter lists
     *                    treat a floor as the member's own level)
     * @param omenSum     the cleared floors' clamped omen, summed
     * @param memberLevel the member's compass level, for the scrap discount
     * @param penalty     0 for going home, {@link #LEAVE_PENALTY} for leaving
     * @param bonusChests the zone's depth bonus for this many floors
     */
    static Settlement settle(List<Integer> floorSteps, List<Integer> floorLevels, int omenSum,
                             int memberLevel, int penalty, int bonusChests) {
        int band = band(omenSum, floorSteps.size(), penalty);
        int chests = Omen.baseRewardChests() + Math.max(0, bonusChests);
        if (floorSteps.isEmpty()) {
            return new Settlement(band, 0, 0, chests);
        }
        int scrap = effectiveScrap(floorSteps, floorLevels, memberLevel);
        return new Settlement(band, chartsOf(scrap), scrap % SCRAP_PER_CHART, chests);
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

    /**
     * {@code "Take home: 2 charts"} plus {@code "3 scrap stays in the dungeon"} when it would.
     * Still the words of the HOME title and the dialog; the wall board uses {@link #homeScreen}.
     */
    static String takeHomeLine(Settlement now) {
        String line = now.levels() > 0
                ? "Take home: " + chartText(now.levels())
                : "Take home: nothing yet";
        if (now.scrapLeft() > 0) {
            line += "\n" + now.scrapLeft() + " scrap stays in the dungeon";
        }
        return line;
    }

    /** The line each member reads when their interval banks. */
    static String bankedLine(Settlement settled, boolean leftEarly) {
        String opening = leftEarly
                ? "You leave at the checkpoint, so the omen counts one band worse. "
                : "Home. ";
        String charts = settled.levels() > 0
                ? "The compass reads " + chartText(settled.levels()) + ": +"
                        + settled.levels() + (settled.levels() == 1 ? " level." : " levels.")
                : "Not enough scrap for a chart.";
        String lost = settled.scrapLeft() > 0
                ? " " + settled.scrapLeft() + " scrap lost."
                : "";
        return opening + charts + lost + " The omen was " + OmenBarText.bandName(settled.band()) + ".";
    }
}
