package pocketdungeons;

/** Q3: the Heard meter's pure rules: it fills by pulses, answers when full, and the unheard pay fires once. */
public class SculkHeardTest {

    public static void main(String[] args) {
        eq(SculkOmen.heardAfter(0, 1), 1);
        eq(SculkOmen.heardAfter(3, 2), 5);
        eq(SculkOmen.heardAfter(2, -4), 2);   // pulses never take the meter down
        eq(SculkOmen.heardAfter(-3, 1), 1);
        check(!SculkOmen.answers(3, 4), "3 of 4 is not yet");
        check(SculkOmen.answers(4, 4), "a full meter answers");
        check(SculkOmen.answers(5, 4), "an overfull meter answers");
        check(!SculkOmen.answers(1, 0), "a room with no ceiling never answers");
        eq(SculkOmen.heardMax(false), 4);
        eq(SculkOmen.heardMax(true), 2);
        // The unheard pay: a spawner, cleared, never heard, not yet paid.
        check(SculkOmen.unheardPays(true, true, false, false), "cleared unheard pays");
        check(!SculkOmen.unheardPays(true, true, true, false), "a room that answered pays nothing");
        check(!SculkOmen.unheardPays(true, true, false, true), "and pays once");
        check(!SculkOmen.unheardPays(true, false, false, false), "an uncleared room pays nothing yet");
        check(!SculkOmen.unheardPays(false, true, false, false), "a room with no spawner has nothing to cross");
        // The Warden wakes at the second answer on the final floor of the Ancient City, once.
        check(!SculkOmen.shouldSummonWarden(true, true, 1, false), "one answer does not wake it");
        check(SculkOmen.shouldSummonWarden(true, true, 2, false), "the second answer does");
        check(!SculkOmen.shouldSummonWarden(true, true, 3, true), "once");
        check(!SculkOmen.shouldSummonWarden(true, false, 3, false), "only on the final floor");
        check(!SculkOmen.shouldSummonWarden(false, true, 3, false), "only in the Ancient City");
        System.out.println("SculkHeardTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
