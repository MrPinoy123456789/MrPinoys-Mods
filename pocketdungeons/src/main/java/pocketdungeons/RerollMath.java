package pocketdungeons;

import java.util.Set;

/**
 * The reroll station's arithmetic, with no Minecraft imports: same discipline
 * as {@link KeystoneMath} and {@link PayoutMath}, for the same reason.
 *
 * <p>M14: a lapis sink orthogonal to fuel (M12). Fuel scales with the ladder;
 * this scales with the gear's own tier, read off the {@code pocketdungeons.tier}
 * custom_data tag M13's loot writes.
 */
final class RerollMath {

    private RerollMath() {}

    /**
     * Lapis cost for rerolling one enchantment on a tier-{@code tier} item.
     * Linear in tier, the same "further in costs more, never so much the door
     * closes" shape {@link KeystoneMath#deplete} uses for the ladder. Tier is
     * clamped to at least 1 so a caller that has not yet validated the tag
     * (0 means "not our gear") cannot ask for a free reroll.
     */
    static int cost(int tier, int lapisPerTier) {
        int clampedTier = Math.max(1, tier);
        return Math.max(0, lapisPerTier) * clampedTier;
    }

    /**
     * Whether swapping {@code before} for {@code after} is a legitimate reroll:
     * the same number of enchantments going in as coming out (nothing vanished),
     * and the set actually changed (a no-op reroll is not a decision). Each set
     * entry is caller-defined (an enchantment id, or an id+level pair); this
     * class does not care what the entries mean, only how many there are and
     * whether they differ.
     *
     * <p>This is the "never strictly worse" guarantee the plan names: a full
     * reroll was rejected because it could produce a strictly worse item, but
     * a one-at-a-time swap with a preserved count and a guaranteed change is a
     * controllable decision, not a gamble that can end with less than what
     * walked in.
     */
    static boolean isValidReroll(Set<String> before, Set<String> after) {
        return after.size() == before.size() && !after.equals(before);
    }
}
