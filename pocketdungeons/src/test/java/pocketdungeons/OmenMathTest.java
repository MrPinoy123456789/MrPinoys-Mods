package pocketdungeons;

/**
 * J3: coverage for {@link Omen}'s pure math in the lives model. Omen is the
 * trip's death count, 0 to 4, read to the player as {@code 5 - omen} lives;
 * the fifth death ends the run. Plus the wave trigger timing (dwell cadence,
 * pulse counts) and the ominous roll.
 */
public class OmenMathTest {

    public static void main(String[] args) {
        testLives();
        testNextDeathFails();
        testClamp();
        testAdd();
        testDwellWaves();
        testPulseWaves();
        testOminousChance();
        testBaseChests();
        System.out.println("OmenMathTest passed");
    }

    /** Lives are 5 - omen: a clean trip reads five, four deaths read one. */
    private static void testLives() {
        check(Omen.lives(0), 5);
        check(Omen.lives(1), 4);
        check(Omen.lives(2), 3);
        check(Omen.lives(3), 2);
        check(Omen.lives(4), 1);
        check(Omen.lives(9), 1);
        check(Omen.lives(-2), 5);
    }

    /** The fifth death is the last: at omen 4 (one life) the next death fails the run. */
    private static void testNextDeathFails() {
        check(!Omen.nextDeathFails(0), "five lives: a death is safe");
        check(!Omen.nextDeathFails(3), "two lives: a death is safe");
        check(Omen.nextDeathFails(4), "one life: the fifth death ends the run");
        check(Omen.nextDeathFails(9), "over the cap still fails");
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

    private static void testAdd() {
        check(Omen.add(3, 1), 4);
        check(Omen.add(4, 1), 4);
        check(Omen.add(0, -1), 0);
        check(Omen.add(1, -5), 0);
        check(Omen.add(1, 1), 2);
        check(Omen.add(3, -1), 2);
    }

    // ---- dwell: a wave per 90 seconds past the first 60 -----------------

    private static void testDwellWaves() {
        check(Omen.dwellWaves(0), 0);
        check(Omen.dwellWaves(60), 0);
        check(Omen.dwellWaves(61), 0);
        check(Omen.dwellWaves(149), 0);
        check(Omen.dwellWaves(150), 1);
        check(Omen.dwellWaves(239), 1);
        check(Omen.dwellWaves(240), 2);
        check(Omen.dwellWaves(330), 3);
        check(Omen.dwellWaves(420), 4);
        // The exemptions: cleared cells and the staging room are free.
        check(Omen.dwellWaves(150, false, false), 0);
        check(Omen.dwellWaves(600, false, false), 0);
        check(Omen.dwellWaves(150, true, true), 0);
        check(Omen.dwellWaves(600, true, true), 0);
        check(Omen.dwellWaves(300, false, true), 0);
        check(Omen.dwellWaves(150, true, false), 1);
        check(Omen.dwellWaves(240, true, false), 2);
        check(Omen.dwellWaves(60, true, false), 0);
    }

    // ---- sensor pulses: a wave per five, per one in the Ancient City ----

    private static void testPulseWaves() {
        check(Omen.pulsesPerWave(false), 5);
        check(Omen.pulsesPerWave(true), 1);
        check(Omen.wavesFromPulses(0, false), 0);
        check(Omen.wavesFromPulses(4, false), 0);
        check(Omen.wavesFromPulses(5, false), 1);
        check(Omen.wavesFromPulses(10, false), 2);
        check(Omen.wavesFromPulses(25, false), 5);
        check(Omen.wavesFromPulses(3, true), 3);
        check(Omen.remainingPulses(7, 1, false), 2);
        check(Omen.remainingPulses(3, 3, true), 0);
        check(Omen.wavesFromPulses(-2, true), 0);
    }

    /** The ominous roll's chance is the share of lives already lost: omen over four. */
    private static void testOminousChance() {
        if (Omen.ominousChance(0) != 0.0 || Omen.ominousChance(-3) != 0.0) {
            throw new AssertionError("a clean trip never rolls ominous");
        }
        if (Math.abs(Omen.ominousChance(2) - 0.5) > 1e-9) {
            throw new AssertionError("two deaths of four is one half");
        }
        if (Omen.ominousChance(4) != 1.0 || Omen.ominousChance(40) != 1.0) {
            throw new AssertionError("the chance is capped at one");
        }
    }

    private static void testBaseChests() {
        check(Omen.baseRewardChests(), 3);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
