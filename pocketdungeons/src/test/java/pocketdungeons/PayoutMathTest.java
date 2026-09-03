package pocketdungeons;

public class PayoutMathTest {

    public static void main(String[] args) {
        testChestCount();
        System.out.println("PayoutMathTest passed");
    }

    private static void testChestCount() {
        // Comfortably inside the 60% threshold.
        check(PayoutMath.chestCount(90, 100, 60, 80), 3);
        // Exactly at the 60% boundary -- inclusive, still three.
        check(PayoutMath.chestCount(40, 100, 60, 80), 3);
        // Just past 60%, inside 80%.
        check(PayoutMath.chestCount(39, 100, 60, 80), 2);
        // Exactly at the 80% boundary -- inclusive, still two.
        check(PayoutMath.chestCount(20, 100, 60, 80), 2);
        // Just past 80%, still within the clock.
        check(PayoutMath.chestCount(19, 100, 60, 80), 1);
        // Right at the buzzer.
        check(PayoutMath.chestCount(1, 100, 60, 80), 1);
        // Over time: no seconds remaining earns nothing.
        check(PayoutMath.chestCount(0, 100, 60, 80), 0);
        check(PayoutMath.chestCount(-5, 100, 60, 80), 0);
        // A zero-length timer must not divide by zero, and still resolves.
        check(PayoutMath.chestCount(0, 0, 60, 80), 0);
        check(PayoutMath.chestCount(5, 0, 60, 80), 3);
        // PD-42: secondsRemaining * 100 overflows int past roughly 21.4
        // million seconds. KeystoneMath.timerSeconds allows a timer up to
        // Integer.MAX_VALUE, so a misconfigured huge timer must still
        // resolve correctly rather than through overflowed garbage.
        // secondsRemaining == totalSeconds here, so 0% of the clock is used
        // and all three chests are earned.
        check(PayoutMath.chestCount(Integer.MAX_VALUE, Integer.MAX_VALUE, 60, 80), 3);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
