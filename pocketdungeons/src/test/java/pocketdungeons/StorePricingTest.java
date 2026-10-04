package pocketdungeons;

/**
 * Pure-JDK regression for the store's pricing and slot composition. Same shape
 * as {@code SalvageMathTest}: no Minecraft classpath, run from {@code tasks.test}.
 */
public class StorePricingTest {

    public static void main(String[] args) {
        testDropPrice();
        testCompositionBounds();
        testCompositionTrim();
        System.out.println("StorePricingTest passed");
    }

    private static void testDropPrice() {
        check(StorePricing.dropPrice(6, 1.0), 6);
        // Bones are worth two per emerald's worth of goods; a pearl is worth two emeralds.
        check(StorePricing.dropPrice(3, 2.0), 6);
        check(StorePricing.dropPrice(4, 0.5), 2);
        // Never free, however valuable the drop.
        check(StorePricing.dropPrice(1, 0.25), 1);
        check(StorePricing.dropPrice(0, 2.0), 1);
    }

    /** Every combination of the four rolls stays inside the spec's ranges and the GUI's seven slots. */
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
