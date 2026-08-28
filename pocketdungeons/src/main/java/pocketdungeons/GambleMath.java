package pocketdungeons;

/**
 * The gamble station's arithmetic, with no Minecraft imports: same discipline
 * as {@link KeystoneMath}, {@link PayoutMath} and {@link RerollMath}.
 *
 * <p>M16: an emerald sink orthogonal to fuel (M12, the ladder-scale sink) and
 * lapis (M14, the gear-scale sink for a piece you already hold). This one
 * trades certainty for volume: no target item, just a slot and a tier. D3's
 * own Kadala menu prices a weapon pull above an armour pull, which is what
 * {@link #cost}'s slot multiplier expresses: one slot (config-named, weapon
 * by default) costs more per tier than the rest.
 */
final class GambleMath {

    private GambleMath() {}

    /**
     * Emerald cost for one gamble draw of {@code slot} at tier {@code tier}.
     * Linear in tier, the same "further in costs more" shape
     * {@link RerollMath#cost} and {@link KeystoneMath#deplete} already use,
     * then scaled by {@code slotMultiplier} if {@code slot} is the weighted
     * one. Tier is clamped to at least 1 so a caller cannot ask for a free
     * draw by passing 0 or a negative tier; the multiplier is never allowed
     * to shrink the cost, so a misconfigured value under 1.0 cannot make the
     * weighted slot the cheap one.
     */
    static int cost(int tier, String slot, int emeraldsPerTier, double slotMultiplier, String weightedSlot) {
        int clampedTier = Math.max(1, tier);
        double base = Math.max(0, emeraldsPerTier) * clampedTier;
        if (weightedSlot != null && weightedSlot.equals(slot)) {
            base *= Math.max(1.0, slotMultiplier);
        }
        return (int) Math.round(base);
    }
}
