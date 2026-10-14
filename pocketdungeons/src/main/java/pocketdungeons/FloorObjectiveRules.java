package pocketdungeons;

/**
 * The floor objectives' arithmetic, with no Minecraft imports so it tests
 * with plain {@code javac} ({@link FloorObjectiveRulesTest}): the fallback
 * rule that turns an unplaceable objective back into a spawner floor, the
 * per-kind quotas (a heist's strongboxes, a claim's veins), the haunt's wave
 * tremor, and a claim floor's bonus rolls.
 */
final class FloorObjectiveRules {

    private FloorObjectiveRules() {}

    /**
     * The objective a floor actually runs: the declared one when its
     * placement put down at least one fixture after quota reduction, and
     * {@link FloorObjective#SPAWNERS} when it placed nothing (the generic
     * recovery rule: an objective that could not be placed must never gate a
     * floor it cannot be finished on).
     */
    static FloorObjective effective(FloorObjective declared, int placed, int required) {
        return placed >= 1 ? declared : FloorObjective.SPAWNERS;
    }

    /**
     * The quota a partially placed objective is held to: never more than was
     * placed, at least one, and 0 when nothing was placed (which
     * {@link #effective} reads as a spawner floor).
     */
    static int effectiveQuota(int required, int placed) {
        return placed > 0 ? Math.max(1, Math.min(required, placed)) : 0;
    }

    /** A heist floor's strongbox count: the base, plus one per two members past the first. */
    static int strongboxes(int base, int members) {
        return base + Math.max(0, members - 1) / 2;
    }

    /** A claim floor's vein count: its quota, the extras sprinkled in, and one more for a full party. */
    static int veins(int quota, int extras, int members) {
        return quota + extras + (members >= 3 ? 1 : 0);
    }

    /** Whether a haunt objective's tremor has reached the count that sends a wave. */
    static boolean waveAt(int tremor, int waveTremor) {
        return tremor >= waveTremor;
    }

    /** The bonus rolls a claim floor pays for every vein claimed past its quota. */
    static int bonusRolls(int claimed, int quota) {
        return Math.max(0, claimed - quota);
    }
}
