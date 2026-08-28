package pocketdungeons;

/**
 * Pure-JDK regression for the gamble station's cost curve. Same shape as
 * {@code RerollMathTest}: no Minecraft classpath, run from {@code tasks.test}.
 */
public class GambleMathTest {

    public static void main(String[] args) {
        testCost();
        testWeightedSlot();
        System.out.println("GambleMathTest passed");
    }

    private static void testCost() {
        check(GambleMath.cost(1, "helmet", 6, 1.5, "weapon"), 6);
        check(GambleMath.cost(2, "helmet", 6, 1.5, "weapon"), 12);
        check(GambleMath.cost(3, "helmet", 6, 1.5, "weapon"), 18);
        // A tier that has not been validated (0 or negative) must not read as
        // a free draw; the caller is responsible for refusing before this is
        // ever called, but the arithmetic itself still floors at tier 1.
        check(GambleMath.cost(0, "helmet", 6, 1.5, "weapon"), 6);
        check(GambleMath.cost(-5, "helmet", 6, 1.5, "weapon"), 6);
        // A misconfigured negative rate never produces a negative cost.
        check(GambleMath.cost(2, "helmet", -5, 1.5, "weapon"), 0);
    }

    private static void testWeightedSlot() {
        // The weighted slot costs more per tier than an unweighted one.
        check(GambleMath.cost(2, "weapon", 6, 1.5, "weapon"), 18);
        check(GambleMath.cost(2, "chestplate", 6, 1.5, "weapon"), 12);
        // A null configured weighted slot must never match anything, rather
        // than throwing on the equals check.
        check(GambleMath.cost(2, "weapon", 6, 1.5, null), 12);
        // A misconfigured multiplier under 1.0 must never make the weighted
        // slot cheaper than the plain tier cost.
        check(GambleMath.cost(2, "weapon", 6, 0.5, "weapon"), 12);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
