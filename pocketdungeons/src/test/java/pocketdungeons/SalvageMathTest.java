package pocketdungeons;

/**
 * Pure-JDK regression for the salvage bench's payouts. Same shape as
 * {@code GambleMathTest}: no Minecraft classpath, run from {@code tasks.test}.
 */
public class SalvageMathTest {

    public static void main(String[] args) {
        testGear();
        testNeverALoop();
        testMobGear();
        testKeys();
        testKeysToFuel();
        System.out.println("SalvageMathTest passed");
    }

    private static void testGear() {
        check(SalvageMath.gearEmeralds(1, 1), 1);
        check(SalvageMath.gearEmeralds(2, 1), 2);
        check(SalvageMath.gearEmeralds(3, 1), 3);
        // An unvalidated tier never reads as free, and a negative rate never pays negative.
        check(SalvageMath.gearEmeralds(0, 1), 1);
        check(SalvageMath.gearEmeralds(2, -4), 0);
    }

    /**
     * The balance guarantee: at the shipped rates, scrapping a gambled piece
     * returns far less than the gamble cost, at every tier and every slot.
     */
    private static void testNeverALoop() {
        for (int tier = 1; tier <= 3; tier++) {
            int cheapest = GambleMath.cost(tier, "helmet", 6, 1.5, "weapon");
            int back = SalvageMath.gearEmeralds(tier, 1);
            if (back * 6 > cheapest) {
                throw new AssertionError("tier " + tier + " salvage " + back + " vs gamble " + cheapest);
            }
        }
    }

    private static void testMobGear() {
        check(SalvageMath.mobGearXp(0), 1);
        check(SalvageMath.mobGearXp(5), 3);
        check(SalvageMath.mobGearXp(-3), 1);
    }

    private static void testKeys() {
        check(SalvageMath.keyEmeralds(4, 1), 4);
        check(SalvageMath.keyEmeralds(2, 3), 6);
        check(SalvageMath.keyEmeralds(-1, 3), 0);
        check(SalvageMath.keyEmeralds(3, -1), 0);
    }

    private static void testKeysToFuel() {
        // Off by default: no fuel, no keys taken.
        check(SalvageMath.keyFuel(7, 0), 0);
        check(SalvageMath.keysForFuel(7, 0), 0);
        // Whole units only; the remainder stays with the player.
        check(SalvageMath.keyFuel(7, 3), 2);
        check(SalvageMath.keysForFuel(7, 3), 6);
        check(SalvageMath.keyFuel(2, 3), 0);
        check(SalvageMath.keysForFuel(2, 3), 0);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
