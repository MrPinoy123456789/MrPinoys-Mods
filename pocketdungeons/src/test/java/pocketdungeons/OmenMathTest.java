package pocketdungeons;

/**
 * M48: coverage for {@link Omen}'s pure math. Every source table row, the
 * clamp at each end, the per-floor sum, the finish table at 3 and 5 floors,
 * the scaling formula, and the dwell exemption for cleared cells.
 */
public class OmenMathTest {

    public static void main(String[] args) {
        testSourceTable();
        testClamp();
        testAddAndSet();
        testFloorSum();
        testFinishTableOneFloor();
        testFinishTableThreeFloors();
        testFinishTableFiveFloors();
        testScaling();
        testBandConsequences();
        testDwellExemption();
        testOminousChance();
        System.out.println("OmenMathTest passed");
    }

    /** The ominous roll's chance is the share of possible omen banked this interval. */
    private static void testOminousChance() {
        if (Omen.ominousChance(0, 0) != 0.0 || Omen.ominousChance(0, 3) != 0.0
                || Omen.ominousChance(5, 0) != 0.0) {
            throw new AssertionError("a calm or empty interval never rolls ominous");
        }
        if (Math.abs(Omen.ominousChance(6, 3) - 0.5) > 1e-9) {
            throw new AssertionError("6 of a possible 12 is one half");
        }
        if (Omen.ominousChance(40, 3) != 1.0) {
            throw new AssertionError("the chance is capped at one");
        }
    }

    // ---- every row of the source table (spec 5.2) -----------------------

    private static void testSourceTable() {
        // Dwell: +1 per 90 seconds beyond the first 60.
        check(Omen.dwellContribution(0), 0);
        check(Omen.dwellContribution(60), 0);
        check(Omen.dwellContribution(61), 0);
        check(Omen.dwellContribution(149), 0);
        check(Omen.dwellContribution(150), 1);
        check(Omen.dwellContribution(239), 1);
        check(Omen.dwellContribution(240), 2);
        check(Omen.dwellContribution(330), 3);
        check(Omen.dwellContribution(420), 4);

        // Sensor pulses: +1 per 5 pulses.
        check(Omen.sensorContribution(0), 0);
        check(Omen.sensorContribution(4), 0);
        check(Omen.sensorContribution(5), 1);
        check(Omen.sensorContribution(9), 1);
        check(Omen.sensorContribution(10), 2);
        check(Omen.sensorContribution(25), 5);

        // Shrieks: +1 each.
        check(Omen.shriekContribution(0), 0);
        check(Omen.shriekContribution(1), 1);
        check(Omen.shriekContribution(3), 3);

        // Ominous Bargain: set to 4.
        check(Omen.bargainOmen(), 4);

        // Barred Vault: -1.
        check(Omen.barredVaultContribution(), -1);
    }

    // ---- clamp at each end (0 and 4) ------------------------------------

    private static void testClamp() {
        check(Omen.clamp(0), 0);
        check(Omen.clamp(2), 2);
        check(Omen.clamp(4), 4);
        check(Omen.clamp(-1), 0);
        check(Omen.clamp(5), 4);
        check(Omen.clamp(-100), 0);
        check(Omen.clamp(100), 4);
    }

    // ---- add and set clamp on the result --------------------------------

    private static void testAddAndSet() {
        // Add clamps at the top.
        check(Omen.add(3, 1), 4);
        check(Omen.add(4, 1), 4);
        check(Omen.add(4, 10), 4);
        // Add clamps at the bottom.
        check(Omen.add(0, -1), 0);
        check(Omen.add(1, -5), 0);
        // Add in the middle does not clamp.
        check(Omen.add(1, 1), 2);
        check(Omen.add(3, -1), 2);
        // Set clamps.
        check(Omen.set(4), 4);
        check(Omen.set(5), 4);
        check(Omen.set(0), 0);
        check(Omen.set(-1), 0);
    }

    // ---- per-floor sum (spec 5.4) ---------------------------------------

    private static void testFloorSum() {
        check(Omen.floorSum(new int[] {0}), 0);
        check(Omen.floorSum(new int[] {4}), 4);
        check(Omen.floorSum(new int[] {0, 0, 0}), 0);
        check(Omen.floorSum(new int[] {4, 4, 4}), 12);
        check(Omen.floorSum(new int[] {1, 2, 3}), 6);
        check(Omen.floorSum(new int[] {4, 4, 4, 4, 4}), 20);
        // Each floor is clamped before summing.
        check(Omen.floorSum(new int[] {5, -1, 3}), 7);
        check(Omen.floorSum(new int[] {100, 100}), 8);
    }

    // ---- finish table at 1 floor (spec 5.2) -----------------------------

    private static void testFinishTableOneFloor() {
        // 0 to 1: low band, 2 to 3: mid band, 4: high band.
        check(Omen.band(0, 1), 0);
        check(Omen.band(1, 1), 0);
        check(Omen.band(2, 1), 1);
        check(Omen.band(3, 1), 1);
        check(Omen.band(4, 1), 2);
    }

    // ---- finish table at 3 floors (spec 5.4) ----------------------------

    private static void testFinishTableThreeFloors() {
        // 0 to 3: low, 4 to 9: mid, 10 to 12: high.
        check(Omen.band(0, 3), 0);
        check(Omen.band(3, 3), 0);
        check(Omen.band(4, 3), 1);
        check(Omen.band(9, 3), 1);
        check(Omen.band(10, 3), 2);
        check(Omen.band(12, 3), 2);
    }

    // ---- finish table at 5 floors (spec 5.4) ----------------------------

    private static void testFinishTableFiveFloors() {
        // 0 to 5: low, 6 to 15: mid, 16 to 20: high.
        check(Omen.band(0, 5), 0);
        check(Omen.band(5, 5), 0);
        check(Omen.band(6, 5), 1);
        check(Omen.band(15, 5), 1);
        check(Omen.band(16, 5), 2);
        check(Omen.band(20, 5), 2);
    }

    // ---- scaling formula at 3 and 5 floors ------------------------------

    private static void testScaling() {
        // The thresholds are 0 to floors, floors+1 to 3*floors,
        // 3*floors+1 to 4*floors. Verify the boundary values.
        for (int floors : new int[] {1, 2, 3, 5, 7}) {
            // Low band top: exactly floors.
            check(Omen.band(floors, floors), 0);
            // Mid band bottom: floors + 1.
            check(Omen.band(floors + 1, floors), 1);
            // Mid band top: 3 * floors.
            check(Omen.band(3 * floors, floors), 1);
            // High band bottom: 3 * floors + 1.
            check(Omen.band(3 * floors + 1, floors), 2);
            // High band top: 4 * floors.
            check(Omen.band(4 * floors, floors), 2);
        }
        // floorsPerSafeVisit of 0 is treated as 1 (defensive).
        check(Omen.band(0, 0), 0);
        check(Omen.band(1, 0), 0);
        check(Omen.band(2, 0), 1);
        check(Omen.band(4, 0), 2);
    }

    // ---- level change and chest count per band (spec 5.2) ---------------

    private static void testBandConsequences() {
        // Low band: +1 level, 3 chests.
        check(Omen.levelChange(0), 1);
        check(Omen.chestCount(0), 3);
        // Mid band: +1 level, 2 chests.
        check(Omen.levelChange(1), 1);
        check(Omen.chestCount(1), 2);
        // High band: +0 level, 1 chest.
        check(Omen.levelChange(2), 0);
        check(Omen.chestCount(2), 1);
    }

    // ---- dwell exemption for cleared cells (spec 5.4) -------------------

    private static void testDwellExemption() {
        // Unsolved cell, not staging: dwelling counts.
        check(Omen.dwellContribution(150, true, false), 1);
        check(Omen.dwellContribution(240, true, false), 2);
        // Cleared cell: free, regardless of time.
        check(Omen.dwellContribution(150, false, false), 0);
        check(Omen.dwellContribution(600, false, false), 0);
        // Staging room: free, regardless of time.
        check(Omen.dwellContribution(150, true, true), 0);
        check(Omen.dwellContribution(600, true, true), 0);
        // Cleared cell that is also the staging room: still free.
        check(Omen.dwellContribution(300, false, true), 0);
        // Short time in an unsolved cell: no contribution yet (under 60s free).
        check(Omen.dwellContribution(60, true, false), 0);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
