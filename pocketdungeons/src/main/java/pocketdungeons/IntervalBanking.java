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

    /**
     * The go-home board, J1 amended (plan 2026-10-06-2 item 4): always
     * {@code GO HOME} (gold, or green once {@code finished}, the moment the
     * dungeon's final floor is cleared), the lives the trip has left, and
     * what leaving forfeits. Scrap never appears: J1 already paid it at
     * each floor clear, so the old {@code carried} table is gone.
     *
     * @param livesLine  {@code "Lives 3"}, the risk meter's reading
     * @param unfinished the promises the trip still holds, {@code vault} and
     *                   {@code page}; rendered {@code Unfinished: vault, page}
     *                   and absent once the dungeon is cleared
     */
    record HomeScreen(String title, boolean finished, String livesLine, List<String> unfinished) {

        /** The forfeit line, or empty when nothing is left to lose. */
        String unfinishedLine() {
            return unfinished.isEmpty() ? "" : "Unfinished: " + String.join(", ", unfinished);
        }

        /** The board as plain text: the lives line, then the forfeit line (when any). */
        String body() {
            String line = unfinishedLine();
            return line.isEmpty() ? livesLine : livesLine + '\n' + line;
        }
    }

    /**
     * The go-home board for pulling the lever right now. {@code omen} is the
     * trip's deaths (J3: the board reads lives); {@code unfinished} is what
     * going home forfeits, which the caller reads off the live record.
     */
    static HomeScreen homeScreen(int omen, boolean finished, List<String> unfinished) {
        return new HomeScreen("GO HOME", finished, OmenBarText.livesText(omen),
                List.copyOf(unfinished));
    }
}
