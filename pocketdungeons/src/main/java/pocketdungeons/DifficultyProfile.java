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
record DifficultyProfile(int pathLength, int partySize, int baseMobs, int maxMobsPerRoom) {

    static DifficultyProfile of(int pathLength, int partySize) {
        return new DifficultyProfile(pathLength, partySize,
                PocketDungeonsConfig.baseMobsPerEncounter(), PocketDungeonsConfig.maxMobsPerRoom());
    }

    /** Path length to loot tier: 5 or shorter is tier 1, 6-7 tier 2, 8 or more tier 3. */
    int lootTier() {
        if (pathLength <= 5) {
            return 1;
        }
        return pathLength <= 7 ? 2 : 3;
    }

    /** The far end of a dungeon bites harder than the first room. */
    int effectiveTier(int depth) {
        int bump = depth >= (pathLength * 2) / 3 ? 1 : 0;
        return Math.min(3, lootTier() + bump);
    }

    /**
     * Mobs for one encounter cell. Depth does not enter the count -- it enters
     * {@link #effectiveTier(int)} instead, keeping "too many" and "too nasty"
     * each mapped to one config field.
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

    /**
     * Extra bonus-pool rolls a party's loot chest gets over a solo player's.
     * Chest loot is shared and first-come; the per-player generosity lever is
     * U5's completion payout, which pays every member individually.
     */
    int chestRolls() {
        return partySize - 1;
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
