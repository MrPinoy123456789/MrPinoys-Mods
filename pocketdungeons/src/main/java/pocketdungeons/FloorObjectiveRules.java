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

    // ---- relic (campaign and endgame, 2026-10-18): one heavy thing, greed and never the gate ----

    /** Whether a door seeded {@code salt} puts a relic on its middle floor: a stable roll against {@code chance} (0 to 1). */
    static boolean relicRolls(long salt, double chance) {
        if (chance <= 0.0) {
            return false;
        }
        if (chance >= 1.0) {
            return true;
        }
        return new java.util.Random(salt * 0x9E3779B97F4A7C15L + 0x5E11C).nextDouble() < chance;
    }

    /**
     * Which cell holds the plinth, as an index into the branch cells followed by the cells of the
     * critical path (both already without the entrance and the terminal): a branch cell when there is
     * one, else a path cell, {@code -1} when the floor has neither (no relic, a spawner floor).
     */
    static int relicCell(int branchCells, int pathCells, long salt) {
        if (branchCells > 0) {
            return (int) Math.floorMod(salt, (long) branchCells);
        }
        if (pathCells > 0) {
            return (int) Math.floorMod(salt, (long) pathCells);
        }
        return -1;
    }

    /**
     * The chests a delivered relic adds: the floor pays {@code baseChests} at its Loot percent, and the
     * relic raises that percent by {@code relicPercent}. Rounded the way {@code Wager.scaledChests} rounds,
     * so the extra is exactly the difference the board would show.
     */
    static int relicBonusRolls(int baseChests, int lootPercent, int relicPercent) {
        if (relicPercent <= 0 || baseChests <= 0) {
            return 0;
        }
        return Math.max(0, Math.round(baseChests * (lootPercent + relicPercent) / 100f)
                - Math.round(baseChests * lootPercent / 100f));
    }

    /**
     * Whether the relic must be put back on its plinth: it is not delivered, and it is nowhere on the floor
     * (not in a member's pack, not on the ground, not in a pot).
     */
    static boolean relicLost(boolean delivered, boolean held, boolean onGround, boolean inPot) {
        return !delivered && !held && !onGround && !inPot;
    }
}
