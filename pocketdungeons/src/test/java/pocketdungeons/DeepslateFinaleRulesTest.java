package pocketdungeons;

import java.nio.file.Files;
import java.nio.file.Path;

import pocketdungeons.DeepslateFinaleRules.Noise;
import pocketdungeons.DeepslateFinaleRules.Rises;

/** Roadmap T31: what each noise adds to the Silent Deep's Heard meter, when it warns, and when it answers. */
public class DeepslateFinaleRulesTest {

    private static Rises defaults() {
        return new Rises(PocketDungeonsConfig.hushPulseRise(), PocketDungeonsConfig.hushBowRise(),
                PocketDungeonsConfig.hushHitRise(), PocketDungeonsConfig.hushDeathRise(),
                PocketDungeonsConfig.hushSprintRise());
    }

    public static void main(String[] args) throws Exception {
        // Each noise adds its own number; a count multiplies it; nothing is ever negative.
        Rises r = new Rises(4, 2, 2, 1, 1);
        eq(r.of(Noise.PULSE), 4);
        eq(r.of(Noise.BOW), 2);
        eq(r.of(Noise.HIT), 2);
        eq(r.of(Noise.DEATH), 1);
        eq(r.of(Noise.SPRINT), 1);
        eq(r.of(Noise.BOW, 3), 6);
        eq(r.of(Noise.BOW, 0), 0);
        eq(r.of(Noise.BOW, -2), 0);
        eq(new Rises(-5, 0, 0, 0, 0).of(Noise.PULSE), 0);

        // The meter is the existing Heard meter: it adds with SculkOmen.heardAfter and answers at the ceiling.
        int ceiling = PocketDungeonsConfig.hushCeiling();
        int heard = 0;
        for (int i = 0; i < 3; i++) {
            heard = SculkOmen.heardAfter(heard, defaults().of(Noise.PULSE));
        }
        check(!DeepslateFinaleRules.full(heard, ceiling), "three pulses do not fill the default ceiling");
        heard = SculkOmen.heardAfter(heard, defaults().of(Noise.PULSE));
        check(DeepslateFinaleRules.full(heard, ceiling), "the fourth pulse does, as the settled law of four");
        check(!DeepslateFinaleRules.full(5, 0), "no ceiling, no answer");

        // The warning line: three quarters, and not once the meter is full.
        check(!DeepslateFinaleRules.nearlyFull(11, 16), "11 of 16 is under three quarters");
        check(DeepslateFinaleRules.nearlyFull(12, 16), "12 of 16 is three quarters");
        check(DeepslateFinaleRules.nearlyFull(15, 16), "15 of 16 is nearly");
        check(!DeepslateFinaleRules.nearlyFull(16, 16), "16 of 16 is the answer, not the warning");
        check(!DeepslateFinaleRules.nearlyFull(3, 0), "no ceiling, no warning");

        // The slow rise: one unit every N seconds, off at 0.
        eq(DeepslateFinaleRules.quietRise(14, 15), 0);
        eq(DeepslateFinaleRules.quietRise(15, 15), 1);
        eq(DeepslateFinaleRules.quietRise(30, 15), 1);
        eq(DeepslateFinaleRules.quietRise(16, 15), 0);
        eq(DeepslateFinaleRules.quietRise(0, 15), 0);
        eq(DeepslateFinaleRules.quietRise(15, 0), 0);
        eq(DeepslateFinaleRules.quietRise(15, -3), 0);

        // The tell: at least 2 seconds, and the answer is due exactly when it has run.
        check(DeepslateFinaleRules.TELL_TICKS >= 40, "a tell of at least 2 seconds");
        eq((int) DeepslateFinaleRules.answerTick(100), 100 + DeepslateFinaleRules.TELL_TICKS);
        check(!DeepslateFinaleRules.answerDue(159, 160), "one tick early");
        check(DeepslateFinaleRules.answerDue(160, 160), "on the tick");
        check(DeepslateFinaleRules.answerDue(161, 160), "and after");

        // Whelps: the knob, kept in range.
        eq(DeepslateFinaleRules.whelps(0), 1);
        eq(DeepslateFinaleRules.whelps(1), 1);
        eq(DeepslateFinaleRules.whelps(2), 2);
        eq(DeepslateFinaleRules.whelps(99), DeepslateFinaleRules.MAX_WHELPS);

        // Defaults: a quiet fight (melee, sneaking) of eight deaths and a minute and a half does not fill the
        // meter by itself; a party that sprints around the room for the same minute does; a lone bowman who
        // fires eight arrows is halfway.
        int quiet = defaults().of(Noise.DEATH, 8);
        for (int second = 1; second <= 90; second++) {
            quiet += DeepslateFinaleRules.quietRise(second, PocketDungeonsConfig.hushQuietSeconds());
        }
        check(quiet < ceiling, "a quiet fight stays under the ceiling (" + quiet + " of " + ceiling + ")");
        check(defaults().of(Noise.SPRINT, 60) >= ceiling, "a minute of sprinting fills it");
        eq(defaults().of(Noise.BOW, 8), ceiling);
        eq(PocketDungeonsConfig.hushWhelps(), 1);

        // The one Warden rule: the real Warden is the Ancient City's alone, and this script spawns none of its own.
        check(!SculkOmen.wardenAllowed("pocketdungeons:deepslate"), "Deepslate never gets the Warden");
        String script = Files.readString(Path.of("src/main/java/pocketdungeons/DeepslateFinale.java"));
        check(!script.contains("EntityTypes.WARDEN"), "the script makes whelps only through Whelp");
        check(script.contains("Whelp.spawn") && script.contains("Whelp.create"), "whelps come from Whelp");

        // One registration line, keyed by the dungeon id, and the room carries a sensor and one shrieker.
        String scripts = Files.readString(Path.of("src/main/java/pocketdungeons/FinaleScripts.java"));
        check(scripts.contains("register(\"pocketdungeons:deepslate\", new DeepslateFinale())"), "registered");
        String specs = Files.readString(Path.of("src/main/java/pocketdungeons/FinalFloorEarlySpecs.java"));
        int from = specs.indexOf("private static RoomSpec silentDeep()");
        int to = specs.indexOf("private static RoomSpec bigFreeze()");
        String room = specs.substring(from, to);
        check(room.contains("SCULK_SENSOR") && room.contains("SCULK_SHRIEKER"), "the room listens");
        check(room.indexOf("SCULK_SHRIEKER") == room.lastIndexOf("SCULK_SHRIEKER"), "one shrieker");
        String data = Files.readString(Path.of("src/main/resources/data/pocketdungeons/dungeon/deepslate.json"));
        check(data.contains("The Deep Sentry") && data.contains("minecraft:bow"), "the Deep Sentry stays");
        System.out.println("DeepslateFinaleRulesTest passed");
    }

    private static void eq(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
