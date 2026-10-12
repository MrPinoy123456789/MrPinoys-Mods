package pocketdungeons;

import java.util.Set;

/**
 * Pure-JDK regression for {@link OverclockedPay}: Overclocked pays extra scrap for a floor cleared under par,
 * through the existing pay path, and never touches the base pay (affix workshop 2026-10-11, row 3).
 */
public class OverclockedPayTest {

    public static void main(String[] args) {
        testWindow();
        testNotOverclocked();
        testPayPathKeepsBase();
        testText();
        testKnobsDefaults();
        System.out.println("OverclockedPayTest passed");
    }

    private static void testWindow() {
        long start = 1000;
        check(OverclockedPay.bonus(true, start, start + 360 * 20L - 1, 360, 1), 1, "just under par pays the bonus");
        check(OverclockedPay.bonus(true, start, start + 360 * 20L, 360, 1), 1, "exactly par still pays");
        check(OverclockedPay.bonus(true, start, start + 360 * 20L + 1, 360, 1), 0, "one tick over par pays nothing");
        check(OverclockedPay.bonus(true, start, start + 5, 360, 3), 3, "the bonus knob is what is paid");
        check(OverclockedPay.bonus(true, start, start + 5, 360, 0), 0, "a bonus of 0 turns it off");
        check(OverclockedPay.bonus(true, start, start + 5, 360, -2), 0, "a negative bonus never takes scrap");
        check(OverclockedPay.bonus(true, 0, 100, 360, 1), 0, "an unknown commit time pays nothing");
        check(OverclockedPay.bonus(true, start, start - 1, 360, 1), 0, "a clock that ran backwards pays nothing");
    }

    private static void testNotOverclocked() {
        check(OverclockedPay.bonus(false, 1000, 1005, 360, 1), 0, "a floor without the affix never gets it");
        check(FloorPay.speedBonus(Set.of(AffixIds.SWARMING), 1000, 1005), 0, "other affixes do not pay for speed");
        check(FloorPay.speedBonus(null, 1000, 1005), 0, "no affix set pays nothing");
        check(FloorPay.speedBonus(Set.of(AffixIds.OVERCLOCKED), 1000, 1005),
                PocketDungeonsConfig.overclockedBonus(), "an Overclocked floor cleared at once pays the knob");
    }

    /** The bonus is an addition: the dealt step is untouched, and a floor dealt no step still pays nothing. */
    private static void testPayPathKeepsBase() {
        int base = ScrapMath.floorPay(2, 10, 10, 0);
        int fast = ScrapMath.floorPay(2, 10, 10, 1);
        check(base, 2, "base pay for step 2");
        check(fast, base + 1, "a fast clear adds exactly the bonus on top");
        check(ScrapMath.floorPay(0, 10, 10, 1), 0, "a floor dealt no step stays at nothing");
        check(ScrapMath.floorPay(2, 9, 10, 1, 50), 1, "below the compass the pay stays the minimum");
    }

    private static void testText() {
        if (!"6:00".equals(OverclockedPay.clock(360))) {
            throw new AssertionError("clock(360) was " + OverclockedPay.clock(360));
        }
        if (!"4:05".equals(OverclockedPay.clock(245))) {
            throw new AssertionError("clock(245) was " + OverclockedPay.clock(245));
        }
        String line = OverclockedPay.titleLine(360, 1);
        if (!"Under 6:00: +1 scrap".equals(line)) {
            throw new AssertionError("title line was " + line);
        }
        if (OverclockedPay.titleLine(360, 0) != null) {
            throw new AssertionError("no bonus, no line");
        }
    }

    private static void testKnobsDefaults() {
        check(PocketDungeonsConfig.overclockedParSeconds(), 360, "default par is 360 seconds");
        check(PocketDungeonsConfig.overclockedBonus(), 1, "default bonus is 1 scrap");
        if (PocketDungeonsConfig.overclockedCooldownFactor() != 0.4) {
            throw new AssertionError("the cooldown factor stays 0.4");
        }
    }

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
