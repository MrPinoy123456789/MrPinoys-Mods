package pocketdungeons;

/** PD-178 and Q10e: the pure part of the effect caps. The clamp itself is covered by EffectCapsGameTest. */
public class EffectCapsTest {

    public static void main(String[] args) {
        eq(EffectCaps.cappedTicks(900, 10), 200);   // a 45 s witch poison is held to 10 s
        eq(EffectCaps.cappedTicks(201, 10), 200);
        eq(EffectCaps.cappedTicks(200, 10), 200);
        eq(EffectCaps.cappedTicks(120, 10), 120);   // shorter is untouched
        eq(EffectCaps.cappedTicks(-1, 10), 200);    // infinite is held too
        eq(EffectCaps.cappedTicks(900, 0), 900);    // 0 turns the cap off
        eq(EffectCaps.cappedTicks(900, 30), 600);
        eq(PocketDungeonsConfig.poisonMaxSeconds(), 10);
        eq(PocketDungeonsConfig.witherMaxSeconds(), 8);
        eq(PocketDungeonsConfig.slownessMaxSeconds(), 6);
        eq(PocketDungeonsConfig.miningFatigueMaxSeconds(), 30);
        System.out.println("EffectCapsTest passed");
    }

    private static void eq(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
