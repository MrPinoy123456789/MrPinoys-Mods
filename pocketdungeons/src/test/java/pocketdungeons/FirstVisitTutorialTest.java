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
        testActZeroTriggersFireTheirOwnSteps();
        testActZeroLinesAreGeneralAndNeverPerFloor();
        testOnceEverPerPlayerForPersonalSteps();
        testFloorsTeachingActZeroLessons();
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

    private static void testActZeroTriggersFireTheirOwnSteps() {
        DungeonLog log = new DungeonLog();
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.COMPASS_TAKEN).equals(
                List.of(FirstVisitTutorial.Step.INTRO)), "closing the first chest with the compass introduces Lemon");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.PROLOGUE_FIRST_FLOOR).equals(
                List.of(FirstVisitTutorial.Step.PAD)), "the first Act 0 floor explains the pad");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.PROLOGUE_THIRD_CLEARED).equals(
                List.of(FirstVisitTutorial.Step.FURNACE)), "the third Act 0 floor explains the furnace");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.PROLOGUE_BEFORE_LAST).equals(
                List.of(FirstVisitTutorial.Step.TRIAL_SPAWNER)), "the floor before the last explains the trial spawner");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.SCRAP_BANKED).equals(
                List.of(FirstVisitTutorial.Step.SCRAP)), "Act 0 banking its scrap explains scrap and Compass 2");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.DIARY_FOUND).equals(
                List.of(FirstVisitTutorial.Step.DIARY)), "the first diary has Lemon say she is looking for Alex");
        check(FirstVisitTutorial.due(log, LEADER, FirstVisitTutorial.Trigger.FIRST_REAL_FLOOR).equals(
                List.of(FirstVisitTutorial.Step.LIVES)), "the first floor after Act 0 explains lives");
    }

    /** Decision 2026-10-17: Lemon's Act 0 lines prepare for dungeons in general and never say what to gather. */
    private static void testActZeroLinesAreGeneralAndNeverPerFloor() {
        String[] gather = {"oak", "log", "cobble", "stone", "coal", "iron ore", "treeline", "ravine", "black seam"};
        for (FirstVisitTutorial.Step step : FirstVisitTutorial.Step.values()) {
            for (String line : step.lines()) {
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                for (String word : gather) {
                    check(!lower.contains(word), step + " gives no per-floor gather instruction (" + word + "): " + line);
                }
                check(!lower.contains("choose a bag") && !lower.contains("refill") && !lower.contains("good pick"),
                        step + " does not talk about choosing a bag or a refilling kit: " + line);
            }
        }
        check(FirstVisitTutorial.Step.INTRO.lines().get(0).contains("Alex's Allay"),
                "Lemon introduces herself as Alex's Allay");
        check(String.join(" ", FirstVisitTutorial.Step.INTRO.lines()).contains("lost Alex"),
                "Lemon says she lost Alex in the pocket dungeons");
        check(String.join(" ", FirstVisitTutorial.Step.SCRAP.lines()).contains("Compass 2"),
                "the scrap lines say what Compass 2 is");
        check(String.join(" ", FirstVisitTutorial.Step.FURNACE.lines()).contains("furnace smelts"),
                "Lemon tells the player what a furnace does");
        check(String.join(" ", FirstVisitTutorial.Step.LIVES.lines()).toLowerCase(java.util.Locale.ROOT).contains("lives"),
                "the lives step explains lives");
    }

    /** Intro, scrap, diary and lives are heard once per player, ever, and a finished one does not return. */
    private static void testOnceEverPerPlayerForPersonalSteps() {
        DungeonLog log = new DungeonLog();
        for (FirstVisitTutorial.Step step : new FirstVisitTutorial.Step[] {FirstVisitTutorial.Step.INTRO,
                FirstVisitTutorial.Step.SCRAP, FirstVisitTutorial.Step.DIARY, FirstVisitTutorial.Step.LIVES}) {
            FirstVisitTutorial.markDone(log, LEADER, step);
            check(FirstVisitTutorial.due(log, LEADER, step.trigger).isEmpty(), step + " is never repeated");
            check(!FirstVisitTutorial.due(log, MEMBER, step.trigger).isEmpty(), step + " is stored per player");
        }
    }

    private static void testFloorsTeachingActZeroLessons() {
        check(FirstVisitTutorial.prologueTrigger(1) == null && FirstVisitTutorial.prologueTrigger(2) == null,
                "floors 1 and 2 teach no extra lesson");
        check(FirstVisitTutorial.prologueTrigger(3) == FirstVisitTutorial.Trigger.PROLOGUE_THIRD_CLEARED,
                "floor 3 teaches the furnace");
        check(FirstVisitTutorial.prologueTrigger(4) == FirstVisitTutorial.Trigger.PROLOGUE_BEFORE_LAST,
                "floor 4 introduces the trial spawner before the last floor");
        check(FirstVisitTutorial.prologueTrigger(5) == null, "the last floor needs no lesson");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
