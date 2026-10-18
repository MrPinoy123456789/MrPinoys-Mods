package pocketdungeons;

public class DifficultyProfileTest {

    public static void main(String[] args) {
        testTierBoundaries();
        testKeystoneRekey();
        testMobScale();
        testCampaignMobCurve();
        testOmenBonusByAct();
        testSpawnersCleared();
        System.out.println("DifficultyProfileTest passed");
    }

    private static void testSpawnersCleared() {
        checkBool(DifficultyProfile.spawnersCleared(0, 0, 0.75), true);
        checkBool(DifficultyProfile.spawnersCleared(3, 4, 0.75), true);
        checkBool(DifficultyProfile.spawnersCleared(2, 4, 0.75), false);
        checkBool(DifficultyProfile.spawnersCleared(4, 4, 0.75), true);
        checkBool(DifficultyProfile.spawnersCleared(0, 4, 0.75), false);
        // Negative "cleared" never reads as more cleared than none.
        checkBool(DifficultyProfile.spawnersCleared(-1, 4, 0.75), false);
    }

    private static void checkBool(boolean actual, boolean expected) {
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    /** M10: base + 1% per level by default, 0.75 at level 0, monotonic across the ladder. */
    private static void testMobScale() {
        checkDouble(DifficultyProfile.mobScale(0, 0.01, 0.75), 0.75);
        checkDouble(DifficultyProfile.mobScale(1, 0.01, 0.75), 0.76);
        checkDouble(DifficultyProfile.mobScale(25, 0.01, 0.75), 1.0);
        checkDouble(DifficultyProfile.mobScale(100, 0.01, 0.75), 1.75);
        // Negative inputs never invert the scale.
        checkDouble(DifficultyProfile.mobScale(-5, 0.01, 0.75), 0.75);
        checkDouble(DifficultyProfile.mobScale(10, -0.5, 0.75), 0.75);
        double previous = 0.0;
        for (int level = 0; level <= 100; level++) {
            double scale = DifficultyProfile.mobScale(level, 0.01, 0.75);
            if (scale < previous) {
                throw new AssertionError("mobScale dipped at level " + level);
            }
            previous = scale;
        }
    }

    /** T1: the campaign defaults, 0.85 + 0.02 per level: level 8 is about vanilla, level 20 is 1.25. */
    private static void testCampaignMobCurve() {
        checkDouble(DifficultyProfile.mobScale(3, 0.02, 0.85), 0.91);
        checkDouble(DifficultyProfile.mobScale(8, 0.02, 0.85), 1.01);
        checkDouble(DifficultyProfile.mobScale(12, 0.02, 0.85), 1.09);
        checkDouble(DifficultyProfile.mobScale(20, 0.02, 0.85), 1.25);
    }

    /** T1: deaths harden the floor from Act 2 only; Acts 0 and 1 use the Act One rate (default 0). */
    private static void testOmenBonusByAct() {
        checkDouble(DifficultyProfile.omenBonus(5, 0, 0.0, 0.1), 0.0);
        checkDouble(DifficultyProfile.omenBonus(5, 1, 0.0, 0.1), 0.0);
        checkDouble(DifficultyProfile.omenBonus(5, 2, 0.0, 0.1), 0.5);
        checkDouble(DifficultyProfile.omenBonus(5, 5, 0.0, 0.1), 0.5);
        // The Act One knob is honoured when an operator raises it, and only for acts 0 and 1.
        checkDouble(DifficultyProfile.omenBonus(4, 1, 0.05, 0.1), 0.2);
        checkDouble(DifficultyProfile.omenBonus(4, 2, 0.05, 0.1), 0.4);
        checkDouble(DifficultyProfile.omenBonus(-3, 2, 0.05, 0.1), 0.0);
    }

    private static void checkDouble(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-9) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void testTierBoundaries() {
        check(DifficultyProfile.of(1, 0).lootTier(), 1);
        check(DifficultyProfile.of(5, 0).lootTier(), 1);
        check(DifficultyProfile.of(6, 0).lootTier(), 2);
        check(DifficultyProfile.of(7, 0).lootTier(), 2);
        check(DifficultyProfile.of(8, 0).lootTier(), 3);
        check(DifficultyProfile.of(20, 0).lootTier(), 3);
    }

    /**
     * U7 re-keys the tier from path length to keystone level. Asserted here so a
     * revert is a test failure.
     *
     * <p>The path-length curve survives only for a run nobody paid a keystone for
     * -- {@code /dungeon admin build} -- so neither half is ever guessing.
     */
    private static void testKeystoneRekey() {
        // keystoneLevel 0: U3's path-length curve, unchanged.
        check(DifficultyProfile.of(5, 0).lootTier(), 1);
        check(DifficultyProfile.of(8, 0).lootTier(), 3);

        // A keystone overrides it completely: a 5-room dungeon on a level 12 key
        // is tier 3, and a 20-room dungeon on a level 1 key is tier 1.
        check(DifficultyProfile.of(5, 12).lootTier(), 3);
        check(DifficultyProfile.of(20, 1).lootTier(), 1);
        check(DifficultyProfile.of(5, 5).lootTier(), 2);

        // The bands are KeystoneMath's, not a second copy of them.
        for (int level = 1; level <= 25; level++) {
            check(DifficultyProfile.of(7, level).lootTier(), KeystoneMath.lootTier(level));
        }
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
