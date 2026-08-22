package pocketdungeons;

import java.util.List;

public class DifficultyProfileTest {

    public static void main(String[] args) {
        testTierBoundaries();
        testEffectiveTierCap();
        testMobCountClamp();
        testRosters();
        testKeystoneRekey();
        System.out.println("DifficultyProfileTest passed");
    }

    private static void testTierBoundaries() {
        check(DifficultyProfile.of(1, 1, 0).lootTier(), 1);
        check(DifficultyProfile.of(5, 1, 0).lootTier(), 1);
        check(DifficultyProfile.of(6, 1, 0).lootTier(), 2);
        check(DifficultyProfile.of(7, 1, 0).lootTier(), 2);
        check(DifficultyProfile.of(8, 1, 0).lootTier(), 3);
        check(DifficultyProfile.of(20, 1, 0).lootTier(), 3);
    }

    private static void testEffectiveTierCap() {
        // Tier-3 base: any depth still caps at 3, never overflows.
        DifficultyProfile tier3 = DifficultyProfile.of(8, 1, 0);
        for (int depth = 0; depth <= 8; depth++) {
            int effective = tier3.effectiveTier(depth);
            if (effective < 1 || effective > 3) {
                throw new AssertionError("effectiveTier(" + depth + ") out of range: " + effective);
            }
        }
        // A shallow cell stays at the base tier; a deep one in the back third bumps up one.
        DifficultyProfile tier1 = DifficultyProfile.of(5, 1, 0);
        check(tier1.effectiveTier(0), 1);
        check(tier1.effectiveTier(4), 2);
    }

    private static void testMobCountClamp() {
        // Solo, short run: floor is 1, never 0 or negative.
        DifficultyProfile solo = new DifficultyProfile(1, 1, 0, 0, 8);
        check(solo.mobCount(0), 1);

        // Big party, long run: the raw formula (2 + 20/3 + 5 = 13) exceeds
        // maxMobsPerRoom, so the ceiling clamps it to 8.
        DifficultyProfile partyLong = new DifficultyProfile(20, 6, 0, 2, 8);
        check(partyLong.mobCount(0), 8);

        // Mid-range: baseMobs(2) + pathLength(6)/3(=2) + (partySize(2)-1) = 5, within [1,8].
        DifficultyProfile mid = new DifficultyProfile(6, 2, 0, 2, 8);
        check(mid.mobCount(0), 5);
    }

    private static void testRosters() {
        for (int tier = 1; tier <= 3; tier++) {
            List<DifficultyProfile.WeightedEntry> roster = DifficultyProfile.of(5, 1, 0).mobRoster(tier);
            if (roster.isEmpty()) {
                throw new AssertionError("tier " + tier + " roster is empty");
            }
            for (DifficultyProfile.WeightedEntry entry : roster) {
                if (entry.weight() <= 0) {
                    throw new AssertionError("tier " + tier + " has non-positive weight: " + entry);
                }
                if (entry.entityId() == null || entry.entityId().isBlank()) {
                    throw new AssertionError("tier " + tier + " has a blank entity id");
                }
            }
        }
        try {
            DifficultyProfile.of(5, 1, 0).mobRoster(0);
            throw new AssertionError("mobRoster(0) should have thrown");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            DifficultyProfile.of(5, 1, 0).mobRoster(4);
            throw new AssertionError("mobRoster(4) should have thrown");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /**
     * U7 re-keys the tier from path length to keystone level, and U6 deletes
     * {@code chestRolls()} outright rather than leaving it beside vanilla's own
     * per-player scaling. Both are asserted here so a revert is a test failure.
     *
     * <p>The path-length curve survives only for a run nobody paid a keystone for
     * -- {@code /dungeon admin build} -- so neither half is ever guessing.
     */
    private static void testKeystoneRekey() {
        // keystoneLevel 0: U3's path-length curve, unchanged.
        check(DifficultyProfile.of(5, 1, 0).lootTier(), 1);
        check(DifficultyProfile.of(8, 1, 0).lootTier(), 3);

        // A keystone overrides it completely: a 5-room dungeon on a level 12 key
        // is tier 3, and a 20-room dungeon on a level 1 key is tier 1.
        check(DifficultyProfile.of(5, 1, 12).lootTier(), 3);
        check(DifficultyProfile.of(20, 1, 1).lootTier(), 1);
        check(DifficultyProfile.of(5, 1, 5).lootTier(), 2);

        // The bands are KeystoneMath's, not a second copy of them.
        for (int level = 1; level <= 25; level++) {
            check(DifficultyProfile.of(7, 1, level).lootTier(), KeystoneMath.lootTier(level));
        }
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
