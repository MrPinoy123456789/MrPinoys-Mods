package pocketdungeons;

/**
 * Pure-JDK regression for the salvage bench's payouts. Same shape as
 * {@code GambleMathTest}: no Minecraft classpath, run from {@code tasks.test}.
 */
public class SalvageMathTest {

    public static void main(String[] args) {
        testGrindstoneXp();
        testKeys();
        testMaterials();
        System.out.println("SalvageMathTest passed");
    }

    /**
     * Owner request (2026-10-03): scrapping pays what a vanilla grindstone
     * would, c to 2c - 1 with c the enchantment cost sum halved rounding up,
     * and nothing for an unenchanted item.
     */
    private static void testGrindstoneXp() {
        check(SalvageMath.grindstoneHalf(0), 0, "an unenchanted item pays no XP");
        check(SalvageMath.grindstoneHalf(-3), 0, "a negative sum pays no XP");
        check(SalvageMath.grindstoneHalf(1), 1, "a sum of 1 rounds up to 1");
        check(SalvageMath.grindstoneHalf(5), 3, "a sum of 5 rounds up to 3");
        check(SalvageMath.grindstoneHalf(6), 3, "a sum of 6 halves to 3");
    }

    private static void testKeys() {
        check(SalvageMath.keyEmeralds(4, 1), 4);
        check(SalvageMath.keyEmeralds(2, 3), 6);
        check(SalvageMath.keyEmeralds(-1, 3), 0);
        check(SalvageMath.keyEmeralds(3, -1), 0);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    /**
     * Owner request (2026-10-03): chestplate and leggings give 2 at 75 percent
     * and above, 1 from 25 to under 75, none below; every other piece gives 1
     * from 25 percent up. Measured against the stack's own (reduced) max.
     */
    private static void testMaterials() {
        check(SalvageMath.materials(true, 40, 40), 2, "a full chestplate gives 2");
        check(SalvageMath.materials(true, 30, 40), 2, "a chestplate at exactly 75 percent gives 2");
        check(SalvageMath.materials(true, 29, 40), 1, "a chestplate just under 75 percent gives 1");
        check(SalvageMath.materials(true, 10, 40), 1, "a chestplate at exactly 25 percent gives 1");
        check(SalvageMath.materials(true, 9, 40), 0, "a chestplate under 25 percent gives nothing");
        check(SalvageMath.materials(false, 48, 64), 1, "a sword at 75 percent of its reduced max gives 1");
        check(SalvageMath.materials(false, 47, 64), 1, "a sword under 75 percent still gives 1");
        check(SalvageMath.materials(false, 16, 64), 1, "a sword at exactly 25 percent gives 1");
        check(SalvageMath.materials(false, 15, 64), 0, "a sword under 25 percent gives nothing");
        check(SalvageMath.materials(false, 3, 64), 0, "a worn sword gives nothing");
        check(SalvageMath.materials(true, 0, 0), 0, "an undamageable item gives nothing");
    }
}
