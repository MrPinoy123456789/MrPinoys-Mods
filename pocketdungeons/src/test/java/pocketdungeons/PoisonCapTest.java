package pocketdungeons;

/** PD-178: the pure part of the poison cap. The clamp itself is covered by PoisonCapGameTest. */
public class PoisonCapTest {

    public static void main(String[] args) {
        eq(PoisonCap.cappedTicks(900, 10), 200);   // a 45 s witch poison is held to 10 s
        eq(PoisonCap.cappedTicks(201, 10), 200);
        eq(PoisonCap.cappedTicks(200, 10), 200);
        eq(PoisonCap.cappedTicks(120, 10), 120);   // shorter poison is untouched
        eq(PoisonCap.cappedTicks(-1, 10), 200);    // infinite poison is held too
        eq(PoisonCap.cappedTicks(900, 0), 900);    // 0 turns the cap off
        eq(PoisonCap.cappedTicks(900, 30), 600);
        eq(PocketDungeonsConfig.poisonMaxSeconds(), 10);
        System.out.println("PoisonCapTest passed");
    }

    private static void eq(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
