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
}
