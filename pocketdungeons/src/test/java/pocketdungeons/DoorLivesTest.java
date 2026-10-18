package pocketdungeons;

/**
 * Pure-JDK regression for {@link DoorLives}: a side door costs a life, the last life is never
 * for sale, and every string is singular or plural as it should be and free of dash punctuation.
 */
public class DoorLivesTest {

    public static void main(String[] args) {
        testAffordable();
        testStrings();
        testBiggerParty();
        System.out.println("DoorLivesTest passed");
    }

    private static void testAffordable() {
        check(DoorLives.affordable(5, 1), "five lives pay one");
        check(DoorLives.affordable(2, 1), "two lives pay one and keep one");
        check(!DoorLives.affordable(1, 1), "the last life is never for sale");
        check(DoorLives.affordable(3, 2), "three lives pay two");
        check(!DoorLives.affordable(2, 2), "two lives cannot pay two");
        check(!DoorLives.affordable(1, 2), "one life cannot pay two");
        check(DoorLives.affordable(1, 0), "a free door is always covered");
        check(DoorLives.affordable(0, 0), "even with no lives left");
    }

    /** A bigger party's trip: the rule is the same, the numbers are higher, the last life is still never for sale. */
    private static void testBiggerParty() {
        check(DoorLives.affordable(8, 2), "eight lives pay two");
        check(DoorLives.affordable(3, 2), "three lives pay two and keep one");
        check(!DoorLives.affordable(2, 2), "two lives cannot pay two");
        // A blood door on a trip that started with eight, after seven deaths: one life, refused.
        int livesLeft = Omen.lives(7, 8);
        check(!DoorLives.affordable(livesLeft, 1), "the last life of an eight life trip is not for sale");
        eq(DoorLives.paidLine("Kris", 7), "Kris paid a life for the side door. Lives 7.");
        eq(DoorLives.paidBloodLine("Kris", 7), "Kris paid a life for the door. Lives 7.");
        eq(DoorLives.takenLine(7), "The door takes a life. Lives 7.");
        eq(DoorLives.refusal(1, 1), "This door costs 1 life and you have 1 left. The last life is never for sale.");
    }

    private static void testStrings() {
        eq(DoorLives.costText(1), "Costs 1 life");
        eq(DoorLives.costText(2), "Costs 2 lives");
        eq(DoorLives.screenRefusal(1, 1), "Costs 1 life. Lives 1: none to spare.");
        eq(DoorLives.refusal(1, 1), "This door costs 1 life and you have 1 left. The last life is never for sale.");
        eq(DoorLives.refusal(2, 2), "This door costs 2 lives and you have 2 left. The last life is never for sale.");
        eq(DoorLives.paidLine("Kris", 4), "Kris paid a life for the side door. Lives 4.");
        eq(DoorLives.takenLine(4), "The door takes a life. Lives 4.");
        for (String line : new String[]{DoorLives.costText(1), DoorLives.costText(2), DoorLives.screenRefusal(1, 2),
                DoorLives.refusal(1, 1), DoorLives.paidLine("A", 1), DoorLives.takenLine(1)}) {
            check(!line.contains("--") && line.indexOf('\u2014') < 0, "no dash punctuation: " + line);
        }
    }

    private static void eq(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected \"" + expected + "\" but got \"" + actual + "\"");
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
