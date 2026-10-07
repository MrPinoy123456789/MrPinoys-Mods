package pocketdungeons;

/**
 * Pure-JDK regression for the store's buy bundles and slot composition.
 * Same shape as {@code SalvageMathTest}: no Minecraft classpath, run from
 * {@code tasks.test}.
 */
public class StorePricingTest {

    public static void main(String[] args) {
        testBuyBundle();
        testNeverBuy();
        testCompositionBounds();
        testCompositionTrim();
        System.out.println("StorePricingTest passed");
    }

    private static void testBuyBundle() {
        // Two bones per emerald at full rate is a 2-for-1 offer.
        check(StorePricing.buyBundle(2.0, 1.0)[0], 2);
        check(StorePricing.buyBundle(2.0, 1.0)[1], 1);
        // The home vendor pays half: four bones for one emerald.
        check(StorePricing.buyBundle(2.0, VendorMath.BUY_RATE)[0], 4);
        check(StorePricing.buyBundle(2.0, VendorMath.BUY_RATE)[1], 1);
        // A drop worth more than an emerald flips the bundle: one drop for
        // several emeralds, and the home vendor halves that too.
        check(StorePricing.buyBundle(0.5, 1.0)[0], 1);
        check(StorePricing.buyBundle(0.5, 1.0)[1], 2);
        check(StorePricing.buyBundle(0.5, VendorMath.BUY_RATE)[0], 1);
        check(StorePricing.buyBundle(0.5, VendorMath.BUY_RATE)[1], 1);
        // Never free, however cheap or valuable the drop.
        check(StorePricing.buyBundle(4.0, 1.0)[1], 1);
        check(StorePricing.buyBundle(0.01, 1.0)[0], 1);
        check(StorePricing.buyBundle(0.01, 1.0)[1] > 0, true);
    }

    /** Gunpowder and sand make TNT; no merchant ever pays emeralds for them. */
    private static void testNeverBuy() {
        check(StorePricing.NEVER_BUY.contains("minecraft:gunpowder"), true);
        check(StorePricing.NEVER_BUY.contains("minecraft:sand"), true);
    }

    /** Every combination of the four rolls stays inside the spec's ranges and the offer slots. */
    private static void testCompositionBounds() {
        for (int mask = 0; mask < 16; mask++) {
            boolean[] rolls = {(mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0};
            int[] c = StorePricing.composition(rolls);
            check(c[0] >= 2 && c[0] <= 3, true);
            check(c[1] >= 1 && c[1] <= 2, true);
            check(c[2] >= 1 && c[2] <= 2, true);
            check(c[3] >= 0 && c[3] <= 1, true);
            check(c[0] + c[1] + c[2] + c[3] <= StorePricing.MAX_SLOTS, true);
        }
    }

    /** The all-extras roll is eight raw slots; the second combat goes first and the rare slot stays. */
    private static void testCompositionTrim() {
        int[] c = StorePricing.composition(new boolean[] {true, true, true, true});
        check(c[0], 3);
        check(c[1], 2);
        check(c[2], 1);
        check(c[3], 1);
        int[] small = StorePricing.composition(new boolean[] {false, false, false, false});
        check(small[0] + small[1] + small[2] + small[3], 4);
    }

    private static void check(Object actual, Object expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
