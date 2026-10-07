package pocketdungeons;

/**
 * Pure-JDK regression for the omen bar's words and numbers: the band progress
 * fill, the titles during and between floors, the completion verdict, the door
 * screen's floor line, the spawner gate's "need" count, and the interval sum
 * the bar reads. No Minecraft classpath, run from {@code tasks.test}.
 */
public class OmenBarTextTest {

    public static void main(String[] args) {
        testOutcome();
        testBandCeilingAndProgress();
        testActiveTitle();
        testClearedTitle();
        testCompletionVerdict();
        testPreviewFloor();
        testRiseLine();
        testSpawnersNeeded();
        testIntervalSum();
        System.out.println("OmenBarTextTest passed");
    }

    private static void testOutcome() {
        checkEquals(OmenBarText.outcome(0), "3 loot rolls, chart scrap");
        checkEquals(OmenBarText.outcome(1), "3 loot rolls, chart scrap");
        checkEquals(OmenBarText.outcome(2), "3 loot rolls, chart scrap");
        // The depth bonus rides on the chest count.
        checkEquals(OmenBarText.outcome(1, 3), "3 loot rolls, chart scrap");
        checkEquals(OmenBarText.outcome(2, 1), "1 loot roll, chart scrap");
    }

    private static void testBandCeilingAndProgress() {
        // Three floors per visit: 0 to 3 low, 4 to 9 mid, 10 to 12 high.
        check(Omen.bandCeiling(0, 3), 3);
        check(Omen.bandCeiling(1, 3), 9);
        check(Omen.bandCeiling(2, 3), 12);
        // Every ceiling agrees with the band it closes, and one more is the next band.
        for (int floors = 1; floors <= 5; floors++) {
            check(Omen.band(Omen.bandCeiling(0, floors), floors), 0);
            check(Omen.band(Omen.bandCeiling(0, floors) + 1, floors), 1);
            check(Omen.band(Omen.bandCeiling(1, floors), floors), 1);
            check(Omen.band(Omen.bandCeiling(1, floors) + 1, floors), 2);
        }
        // The fill is the sum against the next band's first value.
        checkClose(Omen.bandProgress(0, 3), 0.0f);
        checkClose(Omen.bandProgress(2, 3), 0.5f);   // 2 of 4
        checkClose(Omen.bandProgress(5, 3), 0.5f);   // 5 of 10
        checkClose(Omen.bandProgress(12, 3), 1.0f);  // the most three floors can gather
        checkClose(Omen.bandProgress(99, 3), 1.0f);  // clamped
        checkClose(Omen.bandProgress(-3, 3), 0.0f);  // clamped
    }

    private static void testActiveTitle() {
        // The spawner gate leads (playtest 2026-09-26, A1), counted as remaining.
        checkEquals(OmenBarText.activeTitle(2, 2, 5, 8, 6), "1 Spawner remaining | Omen 2/4 | Loot x2");
        checkEquals(OmenBarText.activeTitle(2, 2, 6, 8, 6), "Spawners done | Omen 2/4 | Loot x2");
        checkEquals(OmenBarText.activeTitle(2, 2, 4, 8, 6), "2 Spawners remaining | Omen 2/4 | Loot x2");
        // Omen 0 is hidden; a floor without spawners still shows done.
        checkEquals(OmenBarText.activeTitle(0, 3, -1, -1, 0), "Spawners done | Loot x3");
        checkEquals(OmenBarText.activeTitle(0, 3, 0, 0, 0), "Spawners done | Loot x3");
        // The floor's omen is shown clamped; the death warning follows the floor omen (PD-158):
        // a death adds 1 and 4 ends the run, so it shows from 3, not from band 2.
        checkEquals(OmenBarText.activeTitle(9, 1, 0, 0, 0), "Spawners done | Omen 4/4 | Loot x1 | one more fall ends the run");
        checkEquals(OmenBarText.activeTitle(3, 1, 0, 0, 0), "Spawners done | Omen 3/4 | Loot x1 | one more fall ends the run");
        checkEquals(OmenBarText.activeTitle(2, 1, 0, 0, 0), "Spawners done | Omen 2/4 | Loot x1");
        // The bar's colour during a floor follows the floor omen: 0 to 1 green, 2 to 3 yellow, 4 red.
        int[] expected = {0, 0, 1, 1, 2};
        for (int omen = 0; omen <= 4; omen++) {
            checkEquals(String.valueOf(OmenBarText.omenColourIndex(omen)), String.valueOf(expected[omen]));
        }
        checkEquals(String.valueOf(OmenBarText.omenColourIndex(9)), "2");
        checkEquals(String.valueOf(OmenBarText.omenColourIndex(-1)), "0");
    }

    private static void testClearedTitle() {
        // Dungeon structure W2: the trip length is the dungeon's, so the bar names the dungeon.
        checkEquals(OmenBarText.clearedTitle(2, "Frostworks", false, false, false, 1, 2),
                "Floor 2 of Frostworks cleared | 2 loot rolls, chart scrap");
        checkEquals(OmenBarText.clearedTitle(4, "Frostworks", false, false, true, 0, 3),
                "Floor 4 of Frostworks cleared, final floor ahead | 3 loot rolls, chart scrap");
        checkEquals(OmenBarText.clearedTitle(5, "Frostworks", false, true, false, 1, 3),
                "Frostworks cleared | 3 loot rolls, chart scrap");
        // No dungeon (a run outside the graph) just counts floors; never a trip length.
        checkEquals(OmenBarText.clearedTitle(4, "", false, false, false, 1, 3),
                "Floor 4 cleared | 3 loot rolls, chart scrap");
        checkEquals(OmenBarText.clearedTitle(5, "", true, false, false, 2, 2),
                "Mine floor 5 cleared | 2 loot rolls, chart scrap");
    }

    private static void testCompletionVerdict() {
        checkEquals(OmenBarText.completionVerdict(0, 3), "The omen sits calm: 3 loot rolls, chart scrap so far.");
        checkEquals(OmenBarText.completionVerdict(1, 2), "The omen sits uneasy: 2 loot rolls, chart scrap so far.");
        checkEquals(OmenBarText.completionVerdict(2, 1), "The omen sits dire: 1 loot roll, chart scrap so far.");
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

    private static void testRiseLine() {
        checkEquals(OmenBarText.riseLine(Omen.Source.DWELL, 2), "The walls notice you lingering; more enemies are coming. Omen 2/4.");
        checkEquals(OmenBarText.riseLine(Omen.Source.SHRIEK, 7), "Something below heard that; it is sending company. Omen 4/4.");
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

    private static void testIntervalSum() {
        IntervalState interval = new IntervalState();
        check(interval.omenSum(), 0);
        interval.floorOmens.add(2);
        interval.floorOmens.add(9); // banked clamped
        interval.omen = 1;
        check(interval.bankedOmenSum(), 6);
        check(interval.omenSum(), 7);
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

    private static void checkClose(float actual, float expected) {
        if (Math.abs(actual - expected) > 1e-6f) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void checkEquals(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but was '" + actual + "'");
        }
    }
}
