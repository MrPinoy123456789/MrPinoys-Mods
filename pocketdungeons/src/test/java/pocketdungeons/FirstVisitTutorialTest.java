package pocketdungeons;

import java.util.List;
import java.util.UUID;

/**
 * Lemon's first-visit tour (playtest 2026-10-03, A5): which steps each trigger
 * fires, in what order, that a finished step never comes back, and that the
 * party leader's flags drive it. Pure over {@link DungeonLog}.
 */
public class FirstVisitTutorialTest {

    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000012");

    public static void main(String[] args) {
        testEachTriggerFiresItsOwnStepsInOrder();
        testDoneIsPermanentAndPerStep();
        testTheLeadersFlagsDriveIt();
        testEveryStepHasLinesAndNoDashes();
        System.out.println("FirstVisitTutorialTest passed");
    }

    private static void testEachTriggerFiresItsOwnStepsInOrder() {
        DungeonLog log = new DungeonLog();
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.BAG_CHOSEN).equals(
                List.of(FirstVisitTutorial.Step.BAG_CHEST, FirstVisitTutorial.Step.DOOR_WALL)),
                "a bag choice tours the chest, then the door wall");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.FLOOR_CLEARED).equals(
                List.of(FirstVisitTutorial.Step.ENDER_CHEST, FirstVisitTutorial.Step.SET_OF_THREE)),
                "the first floor cleared tours the ender chest, then the set of three");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.HOME_ARRIVAL).equals(
                List.of(FirstVisitTutorial.Step.WAY_HOME)), "the first arrival home tours the way home");
    }

    private static void testDoneIsPermanentAndPerStep() {
        DungeonLog log = new DungeonLog();
        FirstVisitTutorial.markDone(log, LEADER, FirstVisitTutorial.Step.BAG_CHEST);
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.BAG_CHOSEN).equals(
                List.of(FirstVisitTutorial.Step.DOOR_WALL)), "one finished step leaves the next due");
        for (FirstVisitTutorial.Step step : FirstVisitTutorial.Step.values()) {
            FirstVisitTutorial.markDone(log, LEADER, step);
        }
        for (FirstVisitTutorial.Trigger trigger : FirstVisitTutorial.Trigger.values()) {
            check(FirstVisitTutorial.due(log, LEADER, trigger).isEmpty(), "a finished tour never comes back: " + trigger);
        }
    }

    private static void testTheLeadersFlagsDriveIt() {
        DungeonLog log = new DungeonLog();
        for (FirstVisitTutorial.Step step : FirstVisitTutorial.Step.values()) {
            FirstVisitTutorial.markDone(log, LEADER, step);
        }
        // A member's own flags are untouched by the leader's progress, and the tour reads the leader's.
        check(!FirstVisitTutorial.due(log, MEMBER, FirstVisitTutorial.Trigger.HOME_ARRIVAL).isEmpty(),
                "progress is stored per player, so the tour must be read from the leader's key");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.HOME_ARRIVAL).isEmpty(),
                "the leader, having finished, hears nothing again however many members join");
    }

    private static void testEveryStepHasLinesAndNoDashes() {
        for (FirstVisitTutorial.Step step : FirstVisitTutorial.Step.values()) {
            check(!step.lines().isEmpty(), step + " has something to say");
            for (String line : step.lines()) {
                check(!line.isBlank(), step + " has no blank line");
                check(line.indexOf('\u2014') < 0 && !line.contains(" -- "), step + " uses no dash punctuation: " + line);
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
