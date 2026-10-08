package pocketdungeons;

/**
 * Pure-JDK regression for {@link VendorMath} (J5a): the home vendor's gear
 * prices and the act-driven tier cap. Same shape as {@code SalvageMathTest}:
 * no Minecraft classpath, run from {@code tasks.test}.
 */
public class VendorMathTest {

    public static void main(String[] args) {
        testPrices();
        testTierCap();
        testBuyRate();
        System.out.println("VendorMathTest passed");
    }

    private static void testPrices() {
        // Armour and tools at the 6-per-tier base, weapons at the old
        // weighted-slot 9-per-tier.
        check(VendorMath.price("armour", 1), 6);
        check(VendorMath.price("armour", 2), 12);
        check(VendorMath.price("armour", 3), 18);
        check(VendorMath.price("tool", 3), 18);
        check(VendorMath.price("weapon", 1), 9);
        check(VendorMath.price("weapon", 2), 18);
        check(VendorMath.price("weapon", 3), 27);
        // A bad tier never makes gear free.
        check(VendorMath.price("armour", 0), 6);
        check(VendorMath.price("weapon", -5), 9);
    }

    private static void testTierCap() {
        // J5a's act gate: one tier above the deepest unlocked act, so the
        // shop never outpaces the dungeons. Act 1 sees tiers I and II,
        // Act 2 adds III, and IV is never sold.
        check(VendorMath.tierCap(1), 2);
        check(VendorMath.tierCap(2), 3);
        check(VendorMath.tierCap(5), 3);
        // A player with no recorded act is treated as act 1, not zero stock.
        check(VendorMath.tierCap(0), 2);
    }

    private static void testBuyRate() {
        // The home vendor pays half the dungeon merchants' rate for surplus.
        check(VendorMath.BUY_RATE == 0.5, true);
        check(VendorMath.MENDING_EMERALDS, 64);
    }

    private static void check(Object actual, Object expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
