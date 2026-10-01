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
