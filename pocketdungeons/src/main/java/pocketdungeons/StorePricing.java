package pocketdungeons;

import java.util.Set;

/**
 * Plain store arithmetic with no Minecraft imports, so it can be unit tested.
 *
 * <p>J4 (D36): every store item is priced in emeralds and every drop a
 * merchant buys is paid in emeralds. {@code perEmerald} is how many of that
 * drop a dungeon merchant considers one emerald worth, so a buy bundle is
 * {@code perEmerald} drops for one emerald, with the rounding cases
 * {@link #buyBundle} spells out. Bones are cut to a quarter by
 * {@link DungeonDrops}, so a bone is worth more than a rotten flesh.
 */
final class StorePricing {

    /** Most sell lines a store rolls (the spec's 2 to 3 + 1 to 2 + 1 to 2 + 0 to 1, trimmed). */
    static final int MAX_SLOTS = 7;

    /**
     * Items that are never bought for emeralds (J4): gunpowder and sand make
     * TNT, so paying emeralds for them would buy back the blast budget step
     * 10 is built to spend. Plain item ids so the pure test can check the
     * set without a Minecraft classpath.
     */
    static final Set<String> NEVER_BUY = Set.of("minecraft:gunpowder", "minecraft:sand");

    private StorePricing() {}

    /**
     * A buy offer for one drop: {@code {itemsIn, emeraldsOut}}. One emerald is
     * worth {@code perEmerald} of the drop at a dungeon merchant
     * ({@code rate} 1.0); the home vendor passes 0.5 and so pays half as much
     * per item, keeping the dungeon the better deal. When a single drop is
     * worth more than an emerald ({@code perEmerald} below 1, an ender pearl
     * back when it was bought), the offer takes one drop and pays several
     * emeralds instead. Nothing is ever free.
     */
    static int[] buyBundle(double perEmerald, double rate) {
        double emeraldsPerItem = rate / Math.max(0.01, perEmerald);
        if (emeraldsPerItem >= 1.0) {
            return new int[] {1, Math.max(1, (int) Math.round(emeraldsPerItem))};
        }
        return new int[] {Math.max(1, (int) Math.round(1.0 / emeraldsPerItem)), 1};
    }

    /**
     * How many lines each pool contributes to one store. The shape the spec
     * asked for: common supplies 2 to 3, utility 1 to 2, combat 1 to 2, rare
     * 0 to 1. The raw total (4 to 8) is trimmed to {@link #MAX_SLOTS} by
     * dropping the extras in reverse order of importance (the second combat,
     * then the second utility, then the third common), so the rare line
     * survives when it rolled.
     *
     * @param rolls the four coin flips, in order: third common, second
     *              utility, second combat, rare line
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
