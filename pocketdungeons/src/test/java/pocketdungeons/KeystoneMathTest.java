package pocketdungeons;

/**
 * Pure-JDK regression for the keystone arithmetic: level bands, depletion
 * clamps, fragile doubling and upgrades. No Minecraft classpath, run from
 * {@code tasks.test}.
 *
 * <p>Depletion has a single cause now (a voluntary quit), so the per-outcome
 * cases ({@code death}/{@code exit}/{@code disconnect}) that U7 exercised here
 * are gone; {@link KeystoneMath#deplete} itself is unchanged; only what feeds it
 * changed.
 */
public class KeystoneMathTest {

    public static void main(String[] args) {
        testClamp();
        testLootTierBands();
        testDepletion();
        testFragileDoubling();
        testUpgrade();
        System.out.println("KeystoneMathTest passed");
    }

    private static void testClamp() {
        check(KeystoneMath.clampLevel(1, 25), 1);
        check(KeystoneMath.clampLevel(25, 25), 25);
        check(KeystoneMath.clampLevel(26, 25), 25);
        // A keystone never disappears and never goes to zero -- that is the whole
        // promise the depletion table rests on.
        check(KeystoneMath.clampLevel(0, 25), 1);
        check(KeystoneMath.clampLevel(-40, 25), 1);
        // A nonsense cap must not invert the range.
        check(KeystoneMath.clampLevel(5, 0), 1);
    }

    private static void testLootTierBands() {
        // 1-4 tier 1, 5-9 tier 2, 10+ tier 3. The boundaries are the whole point.
        check(KeystoneMath.lootTier(1), 1);
        check(KeystoneMath.lootTier(4), 1);
        check(KeystoneMath.lootTier(5), 2);
        check(KeystoneMath.lootTier(9), 2);
        check(KeystoneMath.lootTier(10), 3);
        check(KeystoneMath.lootTier(25), 3);
        // Past the cap the loot has stopped moving; only the payout still does.
        check(KeystoneMath.lootTier(500), 3);
    }

    private static void testDepletion() {
        // The one depletion left: timing out, at the shipped default of 1.
        check(KeystoneMath.deplete(8, 1, 1, 25), 7);
        // Failing at level 1 still leaves level 1.
        check(KeystoneMath.deplete(1, 1, 1, 25), 1);
        check(KeystoneMath.deplete(1, 99, 1, 25), 1);
        // Zero depletion (a purge, a restart) never costs anything.
        check(KeystoneMath.deplete(8, 0, 1, 25), 8);
        // A negative depletion is an operator typo, not a free upgrade.
        check(KeystoneMath.deplete(8, -5, 1, 25), 8);
    }

    private static void testFragileDoubling() {
        // Fragile doubles every figure -- the whole cost of having taken the +3.
        check(KeystoneMath.deplete(8, 1, 2, 25), 6);
        check(KeystoneMath.deplete(8, 3, 2, 25), 2);
        // Zero doubled is still zero: a purge never punishes, fragile or not.
        check(KeystoneMath.deplete(8, 0, 2, 25), 8);
        // And it still cannot reach zero.
        check(KeystoneMath.deplete(2, 3, 2, 25), 1);
        // M4 T4.3: a multiplier is never more than 2, whatever the caller passes --
        // two affixes that both double a depletion still cost a double, never a
        // quadruple. AffixMath.depletionMultiplier is what actually enforces
        // "max, not product" across a set; this just confirms deplete() itself
        // cannot be pushed past the cap even by a bad caller.
        check(KeystoneMath.deplete(8, 3, 4, 25), 2);
    }

    private static void testUpgrade() {
        check(KeystoneMath.upgrade(7, 1, 25), 8);
        check(KeystoneMath.upgrade(7, 2, 25), 9);
        check(KeystoneMath.upgrade(7, 3, 25), 10);
        // The cap is honest about where the content ends.
        check(KeystoneMath.upgrade(24, 3, 25), 25);
        check(KeystoneMath.upgrade(25, 1, 25), 25);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
