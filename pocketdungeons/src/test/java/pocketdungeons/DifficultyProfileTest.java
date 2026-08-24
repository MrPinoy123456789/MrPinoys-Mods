package pocketdungeons;

public class DifficultyProfileTest {

    public static void main(String[] args) {
        testTierBoundaries();
        testKeystoneRekey();
        System.out.println("DifficultyProfileTest passed");
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
