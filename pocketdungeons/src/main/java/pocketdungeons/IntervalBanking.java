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

    /**
     * One member's settlement.
     *
     * @param levels    always 0: the haul is banked by {@code DungeonLog#bankHaul}, not here
     * @param scrapLeft always 0, for the same reason
     * @param chests    the chest count the trip's loot roll pays
     */
    record Settlement(int levels, int scrapLeft, int chests) {}

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
        return new Settlement(0, 0, Omen.baseRewardChests() + Math.max(0, bonusChests));
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
     * The go-home board (Haul and Blood Doors): always {@code GO HOME} (gold, or green once
     * {@code finished}), what the party carries, the lives the trip has left, and what leaving
     * forfeits.
     *
     * @param haulLine   solo {@code "Haul 7 scrap"}; a party {@code "Haul: Kris 7, Bob 4"}
     * @param livesLine  {@code "Lives 3"}, the risk meter's reading
     * @param unfinished the promises the trip still holds, {@code vault} and
     *                   {@code page}; rendered {@code Unfinished: vault, page}
     *                   and absent once the dungeon is cleared
     * @param footer     {@code "A failed dungeon keeps half."} while some haul is at risk, else empty
     */
    record HomeScreen(String title, boolean finished, String haulLine, String livesLine,
                      List<String> unfinished, String footer) {

        /** The forfeit line, or empty when nothing is left to lose. */
        String unfinishedLine() {
            return unfinished.isEmpty() ? "" : "Unfinished: " + String.join(", ", unfinished);
        }

        /** The board as plain text: the haul, the lives, the forfeit line (when any), the footer (when any). */
        String body() {
            StringBuilder out = new StringBuilder(haulLine).append('\n').append(livesLine);
            String forfeit = unfinishedLine();
            if (!forfeit.isEmpty()) {
                out.append('\n').append(forfeit);
            }
            if (!footer.isEmpty()) {
                out.append('\n').append(footer);
            }
            return out.toString();
        }
    }

    /** The most names the shared board lists before it summarises. */
    static final int MAX_BOARD_NAMES = 4;

    /**
     * The go-home board for pulling the lever right now. {@code omen} is the trip's deaths (the
     * board reads lives); {@code hauls} is each member's name and haul in party order;
     * {@code unfinished} is what going home forfeits, which the caller reads off the live record.
     */
    static HomeScreen homeScreen(int omen, boolean finished, List<String> unfinished,
                                 java.util.Map<String, Integer> hauls) {
        String haulLine;
        boolean atRisk = false;
        for (int haul : hauls.values()) {
            atRisk |= haul > 0;
        }
        if (hauls.size() <= 1) {
            int haul = hauls.isEmpty() ? 0 : hauls.values().iterator().next();
            haulLine = "Haul " + scrapText(haul);
        } else {
            StringBuilder names = new StringBuilder("Haul: ");
            int shown = 0;
            for (java.util.Map.Entry<String, Integer> entry : hauls.entrySet()) {
                if (shown == MAX_BOARD_NAMES) {
                    names.append(", +").append(hauls.size() - shown);
                    break;
                }
                names.append(shown == 0 ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue());
                shown++;
            }
            haulLine = names.toString();
        }
        return new HomeScreen("GO HOME", finished, haulLine, OmenBarText.livesText(omen),
                List.copyOf(unfinished), !finished && atRisk ? "A failed dungeon keeps half." : "");
    }
}
