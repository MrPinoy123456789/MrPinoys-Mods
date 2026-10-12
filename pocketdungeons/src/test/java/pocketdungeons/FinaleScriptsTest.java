package pocketdungeons;

/** Roadmap T01: the half-dead rule and the finale script registry. */
public class FinaleScriptsTest {

    public static void main(String[] args) {
        // Half dead is alive <= total / 2 with integer division.
        check(!FinaleRules.halfDead(0, 0), "no mobs yet is not half dead");
        check(!FinaleRules.halfDead(8, 8), "a full wave is not half dead");
        check(!FinaleRules.halfDead(5, 8), "5 of 8 alive is not yet");
        check(FinaleRules.halfDead(4, 8), "4 of 8 alive is half dead");
        check(!FinaleRules.halfDead(4, 7), "4 of 7 alive: 7 / 2 is 3, not yet");
        check(FinaleRules.halfDead(3, 7), "3 of 7 alive is half dead");
        check(!FinaleRules.halfDead(1, 1), "a wave of one is half dead only when it is dead");
        check(FinaleRules.halfDead(0, 1), "a wave of one, dead");
        check(!FinaleRules.halfDead(2, 3), "2 of 3 alive: 3 / 2 is 1");
        check(FinaleRules.halfDead(1, 3), "1 of 3 alive");
        // Extras raise the total, which moves the line: 6 alive of 8 is not half, 5 alive of 10 is.
        check(!FinaleRules.halfDead(6, 8), "6 of 8 alive");
        check(FinaleRules.halfDead(5, 10), "5 of 10 alive after extras");

        // Registry: a dungeon without a script gets the no-op script, never null.
        FinaleScripts.registerAll();
        check(FinaleScripts.forDungeon("pocketdungeons:no_such") == FinaleScript.NONE, "unknown dungeon gets NONE");
        check(FinaleScripts.forDungeon(null) == FinaleScript.NONE, "null id gets NONE");
        check(FinaleScripts.forRecord(null) == FinaleScript.NONE, "null record gets NONE");
        FinaleScript mine = new FinaleScript() {};
        FinaleScripts.register("pocketdungeons:script_test", mine);
        check(FinaleScripts.has("pocketdungeons:script_test"), "registered");
        check(FinaleScripts.forDungeon("pocketdungeons:script_test") == mine, "lookup by dungeon id");
        check(FinaleScripts.forDungeon("pocketdungeons:other") == FinaleScript.NONE, "other ids unaffected");
        boolean threw = false;
        try {
            FinaleScripts.register("pocketdungeons:script_test", new FinaleScript() {});
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "a second script for one dungeon is refused");
        FinaleScripts.unregister("pocketdungeons:script_test");
        check(!FinaleScripts.has("pocketdungeons:script_test"), "unregistered");
        FinaleScripts.registerAll();
        System.out.println("FinaleScriptsTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
