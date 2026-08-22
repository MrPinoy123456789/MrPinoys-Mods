package pocketdungeons;

import java.util.List;

/**
 * Pure-logic tier and mob curves for one run. No Minecraft imports, same
 * discipline as {@link DoorMask} and {@link LayoutGraphGenerator} -- testable
 * with plain {@code javac}.
 *
 * <p>Built once at stamp time from the opening player's party size (U3 Stage
 * 5): a mid-run joiner does not trigger a re-stamp, they simply walk into a
 * dungeon scaled for whoever was there when it was built.
 */
record DifficultyProfile(int pathLength, int partySize, int keystoneLevel,
                        int baseMobs, int maxMobsPerRoom) {

    static DifficultyProfile of(int pathLength, int partySize, int keystoneLevel) {
        return new DifficultyProfile(pathLength, partySize, keystoneLevel,
                PocketDungeonsConfig.baseMobsPerEncounter(), PocketDungeonsConfig.maxMobsPerRoom());
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
     * The far end of a dungeon bites harder than the first room.
     *
     * <p><strong>U3 path only.</strong> With trials on, depth escalation is
     * {@code TrialContent.ominousAt} stamping the deep third ominous instead, and
     * nothing calls this. It survives because U3's spawn path is the
     * {@code trialsEnabled: false} rollback and deleting it would cost the only
     * way back.
     */
    int effectiveTier(int depth) {
        int bump = depth >= (pathLength * 2) / 3 ? 1 : 0;
        return Math.min(3, lootTier() + bump);
    }

    /**
     * Mobs for one encounter cell. Depth does not enter the count -- it enters
     * {@link #effectiveTier(int)} instead, keeping "too many" and "too nasty"
     * each mapped to one config field.
     *
     * <p><strong>U3 path only</strong>, same as {@link #effectiveTier(int)}. With
     * trials on the count is the trial spawner's {@code total_mobs} plus its
     * {@code total_mobs_added_per_player}, and vanilla counts the players itself.
     * The {@code partySize} term below is therefore <em>not</em> double-counting:
     * the two paths are mutually exclusive and only one of them is ever live.
     */
    int mobCount(int depth) {
        int count = baseMobs + pathLength / 3 + (partySize - 1);
        return Math.max(1, Math.min(maxMobsPerRoom, count));
    }

    List<WeightedEntry> mobRoster(int tier) {
        return switch (tier) {
            case 1 -> TIER_1;
            case 2 -> TIER_2;
            case 3 -> TIER_3;
            default -> throw new IllegalArgumentException("no mob roster for tier " + tier);
        };
    }

    record WeightedEntry(String entityId, int weight) {}

    /**
     * Excludes creepers (would blow holes in the sealed-cell contract),
     * endermen (teleport out of the instance), and anything that drops a
     * player through the floor -- nothing here can kill a player, so the
     * roster can otherwise be mean.
     */
    private static final List<WeightedEntry> TIER_1 = List.of(
            new WeightedEntry("minecraft:zombie", 5),
            new WeightedEntry("minecraft:skeleton", 4),
            new WeightedEntry("minecraft:spider", 1));

    private static final List<WeightedEntry> TIER_2 = List.of(
            new WeightedEntry("minecraft:zombie", 4),
            new WeightedEntry("minecraft:skeleton", 4),
            new WeightedEntry("minecraft:husk", 2),
            new WeightedEntry("minecraft:stray", 2),
            new WeightedEntry("minecraft:spider", 2),
            new WeightedEntry("minecraft:cave_spider", 1));

    private static final List<WeightedEntry> TIER_3 = List.of(
            new WeightedEntry("minecraft:zombie", 3),
            new WeightedEntry("minecraft:skeleton", 3),
            new WeightedEntry("minecraft:husk", 2),
            new WeightedEntry("minecraft:stray", 2),
            new WeightedEntry("minecraft:spider", 2),
            new WeightedEntry("minecraft:cave_spider", 1),
            new WeightedEntry("minecraft:pillager", 2),
            new WeightedEntry("minecraft:vindicator", 2),
            new WeightedEntry("minecraft:wither_skeleton", 1));
}
