package pocketdungeons;

import java.util.Arrays;
import java.util.List;

/**
 * Pure-JDK regression for the Herobrine Cube's equip-cap arithmetic. Same
 * shape as {@code RerollMathTest} and {@code GambleMathTest}: no Minecraft
 * classpath, run from {@code tasks.test}.
 */
public class PowerEquipMathTest {

    public static void main(String[] args) {
        testUnderCap();
        testOverCap();
        testDuplicatesDoNotDoubleCount();
        testBlanksIgnored();
        testZeroCap();
        System.out.println("PowerEquipMathTest passed");
    }

    private static void testUnderCap() {
        List<String> active = PowerEquipMath.activePowers(Arrays.asList("a", "b", "", "c"), 3);
        check(active, List.of("a", "b", "c"));
    }

    private static void testOverCap() {
        // Five slots, three of them distinct powers, cap 2: only the first two
        // distinct ids encountered in slot order are active.
        List<String> active = PowerEquipMath.activePowers(Arrays.asList("a", "b", "c", "d", "e"), 2);
        check(active, List.of("a", "b"));
    }

    private static void testDuplicatesDoNotDoubleCount() {
        // The same power imbued onto two slots counts once against the cap, so
        // a third distinct power still fits under a cap of 2.
        List<String> active = PowerEquipMath.activePowers(Arrays.asList("a", "a", "b"), 2);
        check(active, List.of("a", "b"));
    }

    private static void testBlanksIgnored() {
        List<String> active = PowerEquipMath.activePowers(Arrays.asList(null, "", "a"), 3);
        check(active, List.of("a"));
    }

    private static void testZeroCap() {
        List<String> active = PowerEquipMath.activePowers(Arrays.asList("a", "b"), 0);
        check(active, List.of());
        // A misconfigured negative cap must not read as "unlimited".
        List<String> negative = PowerEquipMath.activePowers(Arrays.asList("a", "b"), -5);
        check(negative, List.of());
    }

    private static void check(List<String> actual, List<String> expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
