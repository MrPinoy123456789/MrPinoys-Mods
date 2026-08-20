package pocketdungeons;

import java.util.List;

public class DifficultyProfileTest {

    public static void main(String[] args) {
        testTierBoundaries();
        testEffectiveTierCap();
        testMobCountClamp();
        testRosters();
        testChestRolls();
        System.out.println("DifficultyProfileTest passed");
    }

    private static void testTierBoundaries() {
        check(DifficultyProfile.of(1, 1).lootTier(), 1);
        check(DifficultyProfile.of(5, 1).lootTier(), 1);
        check(DifficultyProfile.of(6, 1).lootTier(), 2);
        check(DifficultyProfile.of(7, 1).lootTier(), 2);
        check(DifficultyProfile.of(8, 1).lootTier(), 3);
        check(DifficultyProfile.of(20, 1).lootTier(), 3);
    }

    private static void testEffectiveTierCap() {
        // Tier-3 base: any depth still caps at 3, never overflows.
        DifficultyProfile tier3 = DifficultyProfile.of(8, 1);
        for (int depth = 0; depth <= 8; depth++) {
            int effective = tier3.effectiveTier(depth);
            if (effective < 1 || effective > 3) {
                throw new AssertionError("effectiveTier(" + depth + ") out of range: " + effective);
            }
        }
        // A shallow cell stays at the base tier; a deep one in the back third bumps up one.
        DifficultyProfile tier1 = DifficultyProfile.of(5, 1);
        check(tier1.effectiveTier(0), 1);
        check(tier1.effectiveTier(4), 2);
    }

    private static void testMobCountClamp() {
        // Solo, short run: floor is 1, never 0 or negative.
        DifficultyProfile solo = new DifficultyProfile(1, 1, 0, 8);
        check(solo.mobCount(0), 1);

        // Big party, long run: the raw formula (2 + 20/3 + 5 = 13) exceeds
        // maxMobsPerRoom, so the ceiling clamps it to 8.
        DifficultyProfile partyLong = new DifficultyProfile(20, 6, 2, 8);
        check(partyLong.mobCount(0), 8);

        // Mid-range: baseMobs(2) + pathLength(6)/3(=2) + (partySize(2)-1) = 5, within [1,8].
        DifficultyProfile mid = new DifficultyProfile(6, 2, 2, 8);
        check(mid.mobCount(0), 5);
    }

    private static void testRosters() {
        for (int tier = 1; tier <= 3; tier++) {
            List<DifficultyProfile.WeightedEntry> roster = DifficultyProfile.of(5, 1).mobRoster(tier);
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
            DifficultyProfile.of(5, 1).mobRoster(0);
            throw new AssertionError("mobRoster(0) should have thrown");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            DifficultyProfile.of(5, 1).mobRoster(4);
            throw new AssertionError("mobRoster(4) should have thrown");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void testChestRolls() {
        check(DifficultyProfile.of(5, 1).chestRolls(), 0);
        check(DifficultyProfile.of(5, 4).chestRolls(), 3);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
