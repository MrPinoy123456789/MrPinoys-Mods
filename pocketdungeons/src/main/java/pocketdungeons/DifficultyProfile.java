package pocketdungeons;

/**
 * Pure-logic loot tier for one run. No Minecraft imports, same discipline as
 * {@link DoorMask} and {@link LayoutGraphGenerator} -- testable with plain
 * {@code javac}.
 *
 * <p>T17 deleted the U3 rollback path this record used to carry alongside the
 * tier curve: {@code partySize}, {@code baseMobs}, {@code maxMobsPerRoom},
 * {@code effectiveTier}, {@code mobCount} and {@code mobRoster} are gone along
 * with {@code RoomContent.spawnMobs}, since the trial-spawner path they backed
 * up is now the only path. What survives is the one piece every run still
 * needs regardless of party size: how good the loot is.
 */
record DifficultyProfile(int pathLength, int keystoneLevel) {

    static DifficultyProfile of(int pathLength, int keystoneLevel) {
        return new DifficultyProfile(pathLength, keystoneLevel);
    }

    /**
     * Loot tier.
     *
     * <p>U7 re-keys this from path length to keystone level ({@link KeystoneMath#lootTier}):
     * the planner decides how <em>big</em> a dungeon is, the keystone decides how
     * <em>hard</em> it is, and a tuning complaint maps to one of the two without
     * ambiguity. A run opened without a keystone -- {@code /dungeon admin build},
     * or a server with keystones effectively unused -- keeps U3's path-length
     * curve, so nothing is left without a tier.
     */
    int lootTier() {
        if (keystoneLevel > 0) {
            return KeystoneMath.lootTier(keystoneLevel);
        }
        if (pathLength <= 5) {
            return 1;
        }
        return pathLength <= 7 ? 2 : 3;
    }

    /**
     * M10: mob strength scaling for the run's keystone level, as a multiplier
     * over a mob's base attribute value. {@code base + 0.8% per level} by default
     * (a level-0 run's mobs land at {@code 0.65x}, a level-25 run at {@code 0.85x},
     * a level-100 run at {@code 1.45x}), config-driven via
     * {@link PocketDungeonsConfig#mobScaleBase()} and
     * {@link PocketDungeonsConfig#mobScalePerLevel()}.
     *
     * <p>A level-{@code 0} run (no keystone, {@code /dungeon admin build}) scales
     * to exactly {@code base}: nothing paid for the difficulty, so nothing is
     * added to it beyond the configured floor.
     */
    static double mobScale(int level, double perLevel, double base) {
        return Math.max(0, base) + Math.max(0, level) * Math.max(0, perLevel);
    }

    /**
     * M10's spawner-clear gate: whether enough of the run's trial spawners have
     * reached {@code TrialSpawnerState.COOLDOWN} to let a pad contact complete
     * the run. {@code total == 0} (a run with no spawner-bearing cell) always
     * passes, since there is nothing to gate on.
     */
    static boolean spawnersCleared(int cleared, int total, double threshold) {
        if (total <= 0) {
            return true;
        }
        return (double) Math.max(0, cleared) / total >= threshold;
    }

    /**
     * The fewest cleared spawners out of {@code total} that pass
     * {@link #spawnersCleared}, found by asking it rather than by rounding,
     * so the number shown can never disagree with the gate.
     */
    static int spawnersNeeded(int total, double threshold) {
        for (int n = 0; n < total; n++) {
            if (spawnersCleared(n, total, threshold)) {
                return n;
            }
        }
        return Math.max(0, total);
    }

    /** How many more spawners must clear before the gate passes (PD-111), never negative. */
    static int spawnersStillNeeded(int cleared, int total, double threshold) {
        return Math.max(0, spawnersNeeded(total, threshold) - Math.max(0, cleared));
    }
}
