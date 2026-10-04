package pocketdungeons;

/**
 * Plain store arithmetic with no Minecraft imports, so it can be unit tested.
 *
 * <p>Every store item has one price, written in emeralds. A themed merchant
 * (see {@link MerchantThemes}) takes a mob drop instead, and a drop is worth
 * some number of emeralds: {@code perEmerald} is how many of that drop one
 * emerald is worth. Bones are cut to a quarter by {@link DungeonDrops}, so a
 * bone is worth more than a rotten flesh, and a blaze rod (one per blaze at
 * best) is worth about an emerald.
 */
final class StorePricing {

    /** Most slots a store GUI holds (one row of 9 inside the border). */
    static final int MAX_SLOTS = 7;

    private StorePricing() {}

    /**
     * The price in drops for an item priced in emeralds. {@code perEmerald}
     * below 1 means a drop is worth more than an emerald (an ender pearl). Never
     * below 1, so nothing is free.
     */
    static int dropPrice(int emeraldPrice, double perEmerald) {
        return Math.max(1, (int) Math.round(emeraldPrice * perEmerald));
    }

    /**
     * How many slots each pool contributes to one store. The shape the spec
     * asked for: common supplies 2 to 3, utility 1 to 2, combat 1 to 2, rare
     * 0 to 1. The raw total (4 to 8) is trimmed to {@link #MAX_SLOTS} by
     * dropping the extras in reverse order of importance (the second combat,
     * then the second utility, then the third common), so the rare slot
     * survives when it rolled.
     *
     * @param rolls the four coin flips, in order: third common, second
     *              utility, second combat, rare slot
     * @return {common, utility, combat, rare}
     */
    static int[] composition(boolean[] rolls) {
        int common = rolls[0] ? 3 : 2;
        int utility = rolls[1] ? 2 : 1;
        int combat = rolls[2] ? 2 : 1;
        int rare = rolls[3] ? 1 : 0;
        int over = common + utility + combat + rare - MAX_SLOTS;
        while (over > 0) {
            if (combat > 1) {
                combat--;
            } else if (utility > 1) {
                utility--;
            } else if (common > 2) {
                common--;
            } else {
                break;
            }
            over--;
        }
        return new int[] {common, utility, combat, rare};
    }
}
