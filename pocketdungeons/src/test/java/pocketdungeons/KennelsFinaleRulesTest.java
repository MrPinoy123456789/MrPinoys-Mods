package pocketdungeons;

import java.nio.file.Files;
import java.nio.file.Path;

import pocketdungeons.KennelsFinaleRules.Action;
import pocketdungeons.KennelsFinaleRules.Phase;
import pocketdungeons.KennelsFinaleRules.Step;

/** Roadmap T22: when the handler's windup starts, when it is cancelled, and when the pack shrinks back. */
public class KennelsFinaleRulesTest {

    private static final double F = KennelsFinaleRules.TRIGGER_HEALTH_FRACTION;

    private static Step s(Phase p, long now, long end, boolean handler, boolean alpha, double hp) {
        return KennelsFinaleRules.step(p, now, end, handler, alpha, hp, 40.0, F);
    }

    public static void main(String[] args) throws Exception {
        // The trigger line is half the Alpha's health, inclusive.
        check(!KennelsFinaleRules.atTrigger(21, 40, F), "21 of 40 is above half");
        check(KennelsFinaleRules.atTrigger(20, 40, F), "20 of 40 is half");
        check(!KennelsFinaleRules.atTrigger(0, 0, F), "no maximum, no trigger");

        // Waiting: nothing above the line; the windup starts at it when the handler lives.
        eq(s(Phase.WAITING, 0, 0, true, true, 30), new Step(Phase.WAITING, Action.NONE));
        eq(s(Phase.WAITING, 0, 0, true, true, 20), new Step(Phase.WINDUP, Action.START_WINDUP));
        // A dead Alpha never triggers it.
        eq(s(Phase.WAITING, 0, 0, true, false, 0), new Step(Phase.WAITING, Action.NONE));
        // Handler already dead at half: nothing happens, now or later.
        eq(s(Phase.WAITING, 0, 0, false, true, 20), new Step(Phase.SPENT, Action.NONE));
        eq(s(Phase.SPENT, 5, 0, true, true, 10), new Step(Phase.SPENT, Action.NONE));

        // Windup: holds until its end, then empowers.
        eq(s(Phase.WINDUP, 99, 100, true, true, 20), new Step(Phase.WINDUP, Action.NONE));
        eq(s(Phase.WINDUP, 100, 100, true, true, 20), new Step(Phase.EMPOWERED, Action.EMPOWER));
        // Killing the handler during the windup cancels it, also on the tick it would have ended.
        eq(s(Phase.WINDUP, 50, 100, false, true, 20), new Step(Phase.SPENT, Action.CANCEL));
        eq(s(Phase.WINDUP, 100, 100, false, true, 20), new Step(Phase.SPENT, Action.CANCEL));
        // The Alpha dying in the windup does not stop it.
        eq(s(Phase.WINDUP, 100, 100, true, false, 0), new Step(Phase.EMPOWERED, Action.EMPOWER));

        // Empowered: stays while the handler lives; shrinks once when it dies; then spent.
        eq(s(Phase.EMPOWERED, 500, 100, true, true, 5), new Step(Phase.EMPOWERED, Action.NONE));
        eq(s(Phase.EMPOWERED, 500, 100, false, true, 5), new Step(Phase.SPENT, Action.SHRINK));
        eq(s(Phase.SPENT, 501, 100, false, true, 5), new Step(Phase.SPENT, Action.NONE));

        // A full run in order.
        Phase p = Phase.WAITING;
        Action[] seen = new Action[4];
        long end = 0;
        int i = 0;
        for (long now = 0; now < 400 && i < 4; now++) {
            double hp = now < 10 ? 30 : 20;
            boolean handler = now < 300;
            Step st = s(p, now, end, handler, true, hp);
            p = st.phase();
            if (st.action() == Action.START_WINDUP) {
                end = now + KennelsFinaleRules.WINDUP_TICKS;
            }
            if (st.action() != Action.NONE) {
                seen[i++] = st.action();
            }
        }
        check(seen[0] == Action.START_WINDUP && seen[1] == Action.EMPOWER && seen[2] == Action.SHRINK && seen[3] == null,
                "windup, empower, shrink, in that order, once each");

        // The numbers: 5 second windup, 1.4 times, health bonus off.
        eq(KennelsFinaleRules.WINDUP_TICKS, 100);
        check(KennelsFinaleRules.WINDUP_TICKS >= 40, "a tell of at least 2 seconds");
        check(Math.abs(KennelsFinaleRules.empoweredScale(1.0) - 1.4) < 1e-9, "scale 1.4");
        eq(KennelsFinaleRules.PACK_HEALTH_BONUS_PERCENT, 0);
        check(KennelsFinaleRules.empoweredMaxHealth(8, 0) == 8.0, "no bonus, no change");
        check(KennelsFinaleRules.empoweredMaxHealth(8, 50) == 12.0, "50 percent bonus");
        check(KennelsFinaleRules.healthAfterGrow(8, 8, 12) == 12.0, "a grown wolf gains the difference");
        check(KennelsFinaleRules.healthAfterShrink(12, 8) == 8.0, "shrink caps health");
        check(KennelsFinaleRules.healthAfterShrink(5, 8) == 5.0, "shrink keeps lower health");
        check(KennelsFinaleRules.isPackTeam(KennelsFinaleRules.teamName(7)), "our team name is ours");
        check(!KennelsFinaleRules.isPackTeam("lemon"), "another team is not");

        // Source scans: the handler stays out of Restless (finale tag), and the data wave holds a pillager.
        String restless = Files.readString(Path.of("src/main/java/pocketdungeons/Restless.java"));
        check(restless.contains("NEVER_RISE_TAGS = List.of(FinaleWave.TAG"), "Restless excludes finale mobs");
        String kennels = Files.readString(Path.of("src/main/resources/data/pocketdungeons/dungeon/kennels.json"));
        check(kennels.contains("minecraft:pillager") && kennels.contains("The Alpha"), "kennels finale data");
        System.out.println("KennelsFinaleRulesTest passed");
    }

    private static void eq(Object a, Object b) {
        if (!a.equals(b)) {
            throw new AssertionError(a + " != " + b);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
