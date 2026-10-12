package pocketdungeons;

/** Q8: which deaths raise a mob under Restless, and the pack cap. */
public class RestlessRulesTest {

    public static void main(String[] args) {
        check(RestlessRules.isUndead("minecraft:zombie") && RestlessRules.isUndead("minecraft:skeleton")
                && RestlessRules.isUndead("minecraft:husk") && RestlessRules.isUndead("minecraft:drowned"), "the walking dead rise");
        check(!RestlessRules.isUndead("minecraft:creeper") && !RestlessRules.isUndead("minecraft:spider")
                && !RestlessRules.isUndead("minecraft:wolf") && !RestlessRules.isUndead("minecraft:wither"), "the living are not undead");
        // The roll falls under the chance.
        check(RestlessRules.shouldRise("minecraft:zombie", false, false, 0.3, 0.29), "a roll under the chance rises");
        check(!RestlessRules.shouldRise("minecraft:zombie", false, false, 0.3, 0.30), "a roll at the chance stays down");
        check(!RestlessRules.shouldRise("minecraft:zombie", false, false, 0.0, 0.0), "no chance, no rise");
        check(RestlessRules.shouldRise("minecraft:skeleton", false, false, 0.5, 0.49), "the ominous chance");
        // Fire and a second death keep it down.
        check(!RestlessRules.shouldRise("minecraft:zombie", false, true, 1.0, 0.0), "fire keeps them down");
        check(!RestlessRules.shouldRise("minecraft:zombie", true, false, 1.0, 0.0), "a risen mob does not rise twice");
        check(RestlessRules.shouldRise("minecraft:creeper", false, false, 1.0, 0.0), "the living rise too");
        check(!RestlessRules.shouldRise("minecraft:wither", false, false, 1.0, 0.0), "a boss does not");
        check(!RestlessRules.shouldRise(null, "minecraft:wolf", false, true, false, 1.0, 0.0),
                "an exempt mob (a finale or wave mob, an elite, a pet) does not");
        check(!RestlessRules.shouldRise("ossuary", "minecraft:wolf", true, false, false, 1.0, 0.0), "no second rise");
        check(!RestlessRules.shouldRise("ossuary", "minecraft:wolf", false, false, true, 1.0, 0.0), "fire, themed too");
        check(RestlessRules.shouldRise("ossuary", "minecraft:wolf", false, false, false, 0.3, 0.29), "the roll, themed");

        // Widened: what rises, per theme.
        eqs(RestlessRules.riseType(null, "minecraft:zombie"), "minecraft:zombie");
        eqs(RestlessRules.riseType("pocketdungeons:ossuary", "minecraft:skeleton"), "minecraft:skeleton");
        eqs(RestlessRules.riseType(null, "minecraft:wolf"), "minecraft:skeleton");
        eqs(RestlessRules.riseType(null, "minecraft:spider"), "minecraft:cave_spider");
        eqs(RestlessRules.riseType(null, "minecraft:creeper"), "minecraft:zombie");
        eqs(RestlessRules.riseType("pocketdungeons:frostworks", "minecraft:wolf"), "minecraft:stray");
        eqs(RestlessRules.riseType("frostworks", "minecraft:spider"), "minecraft:stray");
        eqs(RestlessRules.riseType("pocketdungeons:drowned_vault", "minecraft:creeper"), "minecraft:drowned");
        eqs(RestlessRules.riseType("pocketdungeons:frostworks", "minecraft:stray"), "minecraft:stray");
        eqs(RestlessRules.riseType("pocketdungeons:ossuary", "minecraft:husk"), "minecraft:husk");
        eqs(RestlessRules.riseType(null, "minecraft:piglin"), "minecraft:zombified_piglin");
        eqs(RestlessRules.riseType(null, "minecraft:wither"), null);
        eqs(RestlessRules.riseType("pocketdungeons:ossuary", "minecraft:ender_dragon"), null);
        // Every risen type is a vanilla mob that is not a boss.
        java.util.Set<String> risen = new java.util.HashSet<>(RestlessRules.THEME_RISE.values());
        risen.addAll(RestlessRules.KIND_RISE.values());
        risen.add(RestlessRules.DEFAULT_RISE);
        risen.addAll(RestlessRules.UNDEAD);
        for (String type : risen) {
            check(type.startsWith("minecraft:"), "a vanilla type: " + type);
            check(!RestlessRules.NEVER_RISE.contains(type), "a risen type that is not a boss: " + type);
        }
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

    private static void eqs(String actual, String expected) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void eq(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
