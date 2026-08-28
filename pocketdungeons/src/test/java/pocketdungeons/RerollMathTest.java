package pocketdungeons;

import java.util.Set;

/**
 * Pure-JDK regression for the reroll station's cost curve and the "never
 * strictly worse" set-swap property. Same shape as {@code KeystoneMathTest}
 * and {@code PayoutMathTest}: no Minecraft classpath, run from
 * {@code tasks.test}.
 */
public class RerollMathTest {

    public static void main(String[] args) {
        testCost();
        testValidReroll();
        System.out.println("RerollMathTest passed");
    }

    private static void testCost() {
        check(RerollMath.cost(1, 4), 4);
        check(RerollMath.cost(2, 4), 8);
        check(RerollMath.cost(3, 4), 12);
        // A tag that has not been validated as "our gear" (0 or negative) must
        // not read as a free reroll; the caller is responsible for refusing
        // before this is ever called, but the arithmetic itself still floors.
        check(RerollMath.cost(0, 4), 4);
        check(RerollMath.cost(-5, 4), 4);
        // A misconfigured negative rate never produces a negative cost.
        check(RerollMath.cost(2, -5), 0);
    }

    private static void testValidReroll() {
        Set<String> before = Set.of("sharpness:3", "unbreaking:2");
        // A genuine one-for-one swap: same count, actually different.
        Set<String> swapped = Set.of("looting:1", "unbreaking:2");
        if (!RerollMath.isValidReroll(before, swapped)) {
            throw new AssertionError("a same-count, changed set must be a valid reroll");
        }
        // Nothing changed: a no-op reroll is not a decision.
        if (RerollMath.isValidReroll(before, before)) {
            throw new AssertionError("an unchanged set must not read as a valid reroll");
        }
        // An enchantment vanished outright, nothing replaced it.
        Set<String> shrunk = Set.of("unbreaking:2");
        if (RerollMath.isValidReroll(before, shrunk)) {
            throw new AssertionError("a shrunk set must not read as a valid reroll");
        }
        // A set cannot both keep the same size and be a strict subset of the
        // original, confirming the plan's own "not a subset" framing holds.
        Set<String> empty = Set.of();
        if (RerollMath.isValidReroll(empty, empty)) {
            throw new AssertionError("two empty, identical sets must not read as a valid reroll");
        }
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
