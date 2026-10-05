package pocketdungeons;

/**
 * The salvage bench's payout arithmetic, pure JDK so {@code SalvageMathTest}
 * runs headless like {@code GambleMathTest}. {@link SalvageStation} sorts the
 * items and hands the counts here; nothing in this class touches an item.
 * The rates and why they sit where they do are in
 * {@code docs/reference/SALVAGE_PROPOSAL.md}.
 */
final class SalvageMath {

    private SalvageMath() {}

    /**
     * The fixed half of what a vanilla grindstone pays for stripping one
     * item (owner request, 2026-10-03: scrapping pays the grindstone's XP and
     * materials, never emeralds). The grindstone takes the sum of each
     * non-curse enchantment's minimum cost, halves it rounding up to
     * {@code c}, and pays {@code c} plus a random 0 to {@code c - 1}. An
     * unenchanted item pays nothing.
     *
     * @param enchantCostSum the sum of each non-curse enchantment's minimum cost at its level
     * @return {@code c}; the payout is {@code c + random(c)}, so {@code c} to {@code 2c - 1}
     */
    static int grindstoneHalf(int enchantCostSum) {
        return enchantCostSum <= 0 ? 0 : (enchantCostSum + 1) / 2;
    }

    /** How worn a piece is, for {@link #materials}: 75 percent or more left, 25 to under 75, or less. */
    enum Band { HIGH, MID, LOW }

    /**
     * The band for {@code left} of {@code max} durability. {@code max} is the
     * stack's own maximum, so the mod's reduced dungeon durability is what the
     * percentage is taken against. Integer comparisons, so 75 and 25 percent
     * exactly land in the higher band.
     */
    static Band band(int left, int max) {
        if (max <= 0 || left <= 0) {
            return Band.LOW;
        }
        if (left * 4L >= max * 3L) {
            return Band.HIGH;
        }
        return left * 4L >= max ? Band.MID : Band.LOW;
    }

    /**
     * Materials back from one piece of gear (owner request, 2026-10-03):
     * the raw material it is made of, by how worn it is. Deliberately
     * scarce, and never nuggets.
     * <ul>
     *   <li>A chestplate or leggings ({@code large}): 2 in the high band,
     *       1 in the middle band.</li>
     *   <li>Anything else (helmet, boots, weapon, tool): 1 in the high or
     *       middle band.</li>
     *   <li>The low band (under 25 percent): nothing; the piece is worth only
     *       its XP or emeralds.</li>
     * </ul>
     */
    static int materials(boolean large, Band band) {
        return switch (band) {
            case HIGH -> large ? 2 : 1;
            case MID -> 1;
            case LOW -> 0;
        };
    }

    /** As {@link #materials(boolean, Band)}, from raw durability. */
    static int materials(boolean large, int left, int max) {
        return materials(large, band(left, max));
    }

    /** Emeralds for {@code keys} keys at {@code perKey} each; negatives pay nothing. */
    static int keyEmeralds(int keys, int perKey) {
        return Math.max(0, keys) * Math.max(0, perKey);
    }

    /**
     * Fuel for {@code keys} keys at {@code keysPerFuel} keys a unit, or 0 when
     * the trade is off ({@code keysPerFuel <= 0}). Whole units only: the
     * remainder is not bought, see {@link #keysForFuel}.
     */
    static int keyFuel(int keys, int keysPerFuel) {
        return keysPerFuel <= 0 ? 0 : Math.max(0, keys) / keysPerFuel;
    }

    /** How many keys {@link #keyFuel} actually takes; the rest stay with the player. */
    static int keysForFuel(int keys, int keysPerFuel) {
        return keyFuel(keys, keysPerFuel) * Math.max(0, keysPerFuel);
    }
}
