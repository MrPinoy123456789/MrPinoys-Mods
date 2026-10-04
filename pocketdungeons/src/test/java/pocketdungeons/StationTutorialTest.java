package pocketdungeons;

import java.util.UUID;

/**
 * Lemon's station tutorial: what is due at a given keystone level, in what
 * order, and that finishing a step is permanent. Pure over {@link DungeonLog}.
 */
public class StationTutorialTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    public static void main(String[] args) {
        testRankOrderAndLevelGate();
        testDoneIsPermanent();
        testBlockName();
        System.out.println("StationTutorialTest passed");
    }

    private static void testRankOrderAndLevelGate() {
        DungeonLog log = new DungeonLog();
        int salvage = StationTutorial.Step.SALVAGE.unlockLevel();
        check(StationTutorial.next(log, PLAYER, salvage - 1) == null || salvage <= 0, "nothing below the first unlock");
        check(StationTutorial.next(log, PLAYER, salvage) == StationTutorial.Step.SALVAGE, "the salvage bench comes first");
        // A veteran with every unlock gets the earliest unfinished step, one at a time.
        int top = StationTutorial.Step.CUBE.unlockLevel();
        check(StationTutorial.next(log, PLAYER, top) == StationTutorial.Step.SALVAGE, "ranked, not all at once");
        StationTutorial.markDone(log, PLAYER, StationTutorial.Step.SALVAGE);
        check(StationTutorial.next(log, PLAYER, top) == StationTutorial.Step.REROLL, "then the reroll station");
    }

    private static void testDoneIsPermanent() {
        DungeonLog log = new DungeonLog();
        for (StationTutorial.Step step : StationTutorial.Step.values()) {
            StationTutorial.markDone(log, PLAYER, step);
        }
        check(StationTutorial.next(log, PLAYER, 1000) == null, "a finished tutorial never nags");
    }

    private static void testBlockName() {
        check("smithing table".equals(StationTutorial.blockName("minecraft:smithing_table")), "ids read as words");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
