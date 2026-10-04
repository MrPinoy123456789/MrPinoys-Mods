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
     * Emeralds for one piece of tagged gear. Linear in tier like the gamble's
     * cost, so the ratio between the two stays fixed at every tier. An
     * unvalidated tier floors at 1, and a negative rate pays nothing.
     */
    static int gearEmeralds(int tier, int emeraldsPerTier) {
        return Math.max(0, emeraldsPerTier) * Math.max(1, tier);
    }

    /**
     * XP for one piece of untagged gear (a mob drop): one point for the piece
     * plus half of what its enchantments are worth, the way a grindstone pays
     * for the enchantments it strips. Never emeralds: mobs drop these without
     * limit, so emeralds here would let a mob farm print currency.
     *
     * @param enchantCostSum the sum of each enchantment's minimum cost at its level
     */
    static int mobGearXp(int enchantCostSum) {
        return 1 + Math.max(0, enchantCostSum) / 2;
    }

    /**
     * Materials back from one piece of gear (owner request, 2026-10-03): the
     * raw material it is made of, by how much durability is left.
     * Deliberately scarce, and never nuggets.
     * <ul>
     *   <li>A chestplate or leggings ({@code large}): 2 at 75 percent or
     *       more, 1 from 25 to under 75 percent.</li>
     *   <li>Anything else (helmet, boots, weapon, tool): 1 at 75 percent or
     *       more.</li>
     *   <li>Under 25 percent: nothing; the piece is worth only its XP or
     *       emeralds.</li>
     * </ul>
     * {@code max} is the stack's own maximum, so the mod's reduced dungeon
     * durability is what the percentage is taken against.
     *
     * @param left durability remaining
     * @param max  the stack's maximum durability; 0 or less pays nothing
     */
    static int materials(boolean large, int left, int max) {
        if (max <= 0 || left <= 0) {
            return 0;
        }
        // Integer comparison: left / max >= 3 / 4 without rounding.
        if (left * 4L >= max * 3L) {
            return large ? 2 : 1;
        }
        if (large && left * 4L >= max) {
            return 1;
        }
        return 0;
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
