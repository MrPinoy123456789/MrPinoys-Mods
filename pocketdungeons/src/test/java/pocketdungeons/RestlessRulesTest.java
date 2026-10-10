package pocketdungeons;

/** Q8: which deaths raise a mob under Restless, and the pack cap. */
public class RestlessRulesTest {

    public static void main(String[] args) {
        check(RestlessRules.isUndead("minecraft:zombie") && RestlessRules.isUndead("minecraft:skeleton")
                && RestlessRules.isUndead("minecraft:husk") && RestlessRules.isUndead("minecraft:drowned"), "the walking dead rise");
        check(!RestlessRules.isUndead("minecraft:creeper") && !RestlessRules.isUndead("minecraft:spider")
                && !RestlessRules.isUndead("minecraft:wolf") && !RestlessRules.isUndead("minecraft:wither"), "the living and the bosses do not");
        // The roll falls under the chance.
        check(RestlessRules.shouldRise("minecraft:zombie", false, false, 0.3, 0.29), "a roll under the chance rises");
        check(!RestlessRules.shouldRise("minecraft:zombie", false, false, 0.3, 0.30), "a roll at the chance stays down");
        check(!RestlessRules.shouldRise("minecraft:zombie", false, false, 0.0, 0.0), "no chance, no rise");
        check(RestlessRules.shouldRise("minecraft:skeleton", false, false, 0.5, 0.49), "the ominous chance");
        // Fire and a second death keep it down.
        check(!RestlessRules.shouldRise("minecraft:zombie", false, true, 1.0, 0.0), "fire keeps them down");
        check(!RestlessRules.shouldRise("minecraft:zombie", true, false, 1.0, 0.0), "a risen mob does not rise twice");
        check(!RestlessRules.shouldRise("minecraft:creeper", false, false, 1.0, 0.0), "only the undead");
        check(RestlessRules.RISE_DELAY_TICKS == 40, "two seconds to see it coming");

        // The pack cap: how many tamed wolves are beyond it.
        eq(PetCapRules.excess(0, 3), 0);
        eq(PetCapRules.excess(3, 3), 0);
        eq(PetCapRules.excess(5, 3), 2);
        eq(PetCapRules.excess(2, 0), 2);
        eq(PetCapRules.excess(-1, 3), 0);
        System.out.println("RestlessRulesTest passed");
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
