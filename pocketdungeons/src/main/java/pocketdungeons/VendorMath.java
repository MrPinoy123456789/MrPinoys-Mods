package pocketdungeons;

/**
 * The home vendor's arithmetic (J5a), with no Minecraft imports, same
 * discipline as {@link RerollMath}.
 *
 * <p>Prices carry over from the gamble station they replace: a weapon pull
 * was the weighted slot at 1.5x the 6-per-tier base, so weapons sell at 9
 * per tier and everything else at 6. The Mending book is a flat 64, the old
 * lock-in price doubled for being always available.
 */
final class VendorMath {

    /** Emeralds for the Mending book, always in stock. */
    static final int MENDING_EMERALDS = 64;
    /** The home vendor pays half the dungeon merchants' rate for surplus (J5a). */
    static final double BUY_RATE = 0.5;
    /** Emeralds per tier for armour and tool offers. */
    static final int PER_TIER = 6;
    /** Emeralds per tier for weapon offers (the old weighted gamble slot). */
    static final int WEAPON_PER_TIER = 9;

    private VendorMath() {}

    /** Emerald price for one {@code category} offer at {@code tier} (1 to 3). */
    static int price(String category, int tier) {
        int perTier = "weapon".equals(category) ? WEAPON_PER_TIER : PER_TIER;
        return perTier * Math.max(1, tier);
    }

    /**
     * The highest gear tier the vendor stocks for a player whose deepest
     * unlocked act is {@code highestAct}: one tier above it, clamped to the
     * authored tiers, so the shop never outpaces the dungeons (Act 1: tiers
     * I and II; Act 2 and deeper add III; IV is never sold).
     */
    static int tierCap(int highestAct) {
        return Math.min(3, Math.max(2, highestAct + 1));
    }
}
