package pocketdungeons;

/**
 * Pure-JDK regression for the lives bar's words and numbers (J3): the titles
 * during and between floors, the completion verdict, the death cue, the wave
 * cue lines, the door screen's floor line, the spawner gate's "need" count,
 * and the interval's omen read. No Minecraft classpath, run from
 * {@code tasks.test}.
 */
public class OmenBarTextTest {

    public static void main(String[] args) {
        testOutcome();
        testLivesText();
        testActiveTitle();
        testClearedTitle();
        testCompletionVerdict();
        testDeathCue();
        testPreviewFloor();
        testCueLine();
        testIntervalOmen();
        testSpawnersNeeded();
        System.out.println("OmenBarTextTest passed");
    }

    private static void testOutcome() {
        checkEquals(OmenBarText.outcome(), "3 loot rolls, chart scrap");
        checkEquals(OmenBarText.outcome(3), "3 loot rolls, chart scrap");
        checkEquals(OmenBarText.outcome(1), "1 loot roll, chart scrap");
    }

    private static void testLivesText() {
        checkEquals(OmenBarText.livesText(0), "Lives 5");
        checkEquals(OmenBarText.livesText(2), "Lives 3");
        checkEquals(OmenBarText.livesText(4), "Lives 1");
        checkEquals(OmenBarText.livesText(9), "Lives 1");
    }

    private static void testActiveTitle() {
        // J3: the spawner gate leads, lives follow ("Spawners 2/3 | Lives 3").
        checkEquals(OmenBarText.activeTitle(2, 2, 2, 8, 3), "Spawners 2/3 | Lives 3");
        checkEquals(OmenBarText.activeTitle(2, 2, 5, 8, 6), "Spawners 5/6 | Lives 3");
        checkEquals(OmenBarText.activeTitle(2, 2, 6, 8, 6), "Spawners done | Lives 3");
        // A floor without spawners still shows done; lives are always shown.
        checkEquals(OmenBarText.activeTitle(0, 3, -1, -1, 0), "Spawners done | Lives 5");
        checkEquals(OmenBarText.activeTitle(0, 3, 0, 0, 0), "Spawners done | Lives 5");
        // One life warns that the next death ends the run.
        checkEquals(OmenBarText.activeTitle(9, 1, 0, 0, 0),
                "Spawners done | Lives 1 | one more fall ends the run");
        checkEquals(OmenBarText.activeTitle(4, 1, 0, 0, 0),
                "Spawners done | Lives 1 | one more fall ends the run");
        checkEquals(OmenBarText.activeTitle(3, 1, 0, 0, 0), "Spawners done | Lives 2");
        // The bar's colour follows the lives: 5 and 4 green, 3 and 2 yellow, 1 red.
        int[] expected = {0, 0, 1, 1, 2};
        for (int omen = 0; omen <= 4; omen++) {
            checkEquals(String.valueOf(OmenBarText.omenColourIndex(omen)), String.valueOf(expected[omen]));
        }
        checkEquals(String.valueOf(OmenBarText.omenColourIndex(9)), "2");
        checkEquals(String.valueOf(OmenBarText.omenColourIndex(-1)), "0");
    }

    private static void testClearedTitle() {
        // Between floors: the headline, what going home pays, and the lives left.
        // PD-174: the bar keeps the floor and the lives; the loot outcome is in chat.
        checkEquals(OmenBarText.clearedTitle(2, "Frostworks", false, false, false, 1, 2),
                "Floor 2 of Frostworks cleared | Lives 4");
        checkEquals(OmenBarText.clearedTitle(4, "Frostworks", false, false, true, 0, 3),
                "Floor 4 of Frostworks cleared, final floor ahead | Lives 5");
        checkEquals(OmenBarText.clearedTitle(5, "Frostworks", false, true, false, 1, 3),
                "Frostworks cleared | Lives 4");
        // No dungeon (a run outside the graph) just counts floors; never a trip length.
        checkEquals(OmenBarText.clearedTitle(4, "", false, false, false, 1, 3),
                "Floor 4 cleared | Lives 4");
        checkEquals(OmenBarText.clearedTitle(5, "", true, false, false, 2, 2),
                "Mine floor 5 cleared | Lives 3");
        // The big screen title never carries the final-floor tail; the subtitle does.
        checkEquals(OmenBarText.clearedScreenTitle(4, "Frostworks", false, false), "Floor 4 of Frostworks cleared");
        checkEquals(OmenBarText.clearedSubtitle(false, true), "Final floor ahead: GO HOME or DESCEND");
        checkEquals(OmenBarText.clearedSubtitle(false, false), "GO HOME or DESCEND");
        checkEquals(OmenBarText.clearedSubtitle(true, false), "GO HOME");
        for (String line : new String[] {
                OmenBarText.clearedTitle(4, "Ancient City", false, false, true, 0, 3),
                OmenBarText.clearedScreenTitle(4, "Ancient City", false, false)}) {
            if (line.contains(" | ") && line.length() > 60) {
                throw new AssertionError("too long for one line: " + line);
            }
        }
    }

    private static void testCompletionVerdict() {
        checkEquals(OmenBarText.completionVerdict(0, 3), "5 lives left: 3 loot rolls, chart scrap so far.");
        checkEquals(OmenBarText.completionVerdict(1, 2), "4 lives left: 2 loot rolls, chart scrap so far.");
        checkEquals(OmenBarText.completionVerdict(4, 1), "1 life left: 1 loot roll, chart scrap so far.");
    }

    private static void testDeathCue() {
        // J3's example: "A bad omen. 2 lives left."
        checkEquals(OmenBarText.deathCue(3), "A bad omen. 2 lives left.");
        checkEquals(OmenBarText.deathCue(1), "A bad omen. 4 lives left.");
        checkEquals(OmenBarText.deathCue(4), "A bad omen. 1 life left.");
    }

    private static void testPreviewFloor() {
        // PD-152 (playtest 2026-10-05-1): dungeon first, no keystone level.
        checkEquals(OmenBarText.previewFloor(1, "Frostworks", false, false), "FROSTWORKS: FLOOR 1");
        checkEquals(OmenBarText.previewFloor(3, "Deepslate", false, false), "DEEPSLATE: FLOOR 3");
        checkEquals(OmenBarText.previewFloor(5, "Frostworks", false, true), "FROSTWORKS: FINAL FLOOR");
        checkEquals(OmenBarText.previewFloor(4, "", false, false), "FLOOR 4");
        checkEquals(OmenBarText.previewFloor(4, "", false, true), "FINAL FLOOR");
        checkEquals(OmenBarText.previewFloor(7, "Frostworks", true, false), "MINE FLOOR 7");
    }

    private static void testCueLine() {
        checkEquals(OmenBarText.cueLine(Omen.Source.DWELL),
                "The walls notice you lingering; more enemies are coming.");
        checkEquals(OmenBarText.cueLine(Omen.Source.SHRIEK),
                "Something below heard that; it is sending company.");
        checkEquals(OmenBarText.cueLine(Omen.Source.BARGAIN),
                "The bargain is struck; the floor fights two levels harder.");
        // Dwell is held back hardest; a bargain is never held back.
        check(OmenBarText.cueCooldownTicks(Omen.Source.BARGAIN), 0);
        if (OmenBarText.cueCooldownTicks(Omen.Source.DWELL) <= OmenBarText.cueCooldownTicks(Omen.Source.SENSOR)) {
            throw new AssertionError("dwell should be rate limited harder than sensor pulses");
        }
        // PD-100: the sensor line explains a mechanic, so it is held on screen
        // past the client's three second fade; the other cues are one-shot.
        if (OmenBarText.holdTicks(Omen.Source.SENSOR) < 20 * 5) {
            throw new AssertionError("the sensor line should be held long enough to read");
        }
        check(OmenBarText.holdTicks(Omen.Source.DWELL), 0);
    }

    private static void testIntervalOmen() {
        // J3: the interval's omen is the trip's death count; floorOmens are a
        // journal record and never feed it back.
        IntervalState interval = new IntervalState();
        check(interval.omenSum(), 0);
        interval.floorOmens.add(2);
        interval.floorOmens.add(9);
        interval.omen = 1;
        check(interval.bankedOmenSum(), 1);
        check(interval.omenSum(), 1);
    }

    private static void testSpawnersNeeded() {
        check(DifficultyProfile.spawnersNeeded(8, 0.75), 6);
        check(DifficultyProfile.spawnersNeeded(4, 0.75), 3);
        check(DifficultyProfile.spawnersNeeded(5, 0.75), 4);
        check(DifficultyProfile.spawnersStillNeeded(3, 5, 0.75), 1);
        check(DifficultyProfile.spawnersStillNeeded(1, 8, 0.75), 5);
        check(DifficultyProfile.spawnersStillNeeded(9, 8, 0.75), 0);
        check(DifficultyProfile.spawnersNeeded(1, 0.75), 1);
        check(DifficultyProfile.spawnersNeeded(0, 0.75), 0);
        check(DifficultyProfile.spawnersNeeded(6, 1.0), 6);
        // Whatever it says always passes the gate, and one fewer never does.
        for (int total = 1; total <= 20; total++) {
            int need = DifficultyProfile.spawnersNeeded(total, 0.75);
            checkTrue(DifficultyProfile.spawnersCleared(need, total, 0.75));
            checkTrue(!DifficultyProfile.spawnersCleared(need - 1, total, 0.75));
        }
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void checkTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("expected true");
        }
    }

    private static void checkEquals(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but was '" + actual + "'");
        }
    }
}
