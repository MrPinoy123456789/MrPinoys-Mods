package pocketdungeons;

import java.util.UUID;

/**
 * Lemon's station tutorial: J5 dropped the unlock levels, so the tutorial is
 * rank order over an unfinished-steps set only. Pure over {@link DungeonLog}.
 */
public class StationTutorialTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    public static void main(String[] args) {
        testRankOrder();
        testDoneIsPermanent();
        testBlockName();
        testBlockIds();
        System.out.println("StationTutorialTest passed");
    }

    private static void testRankOrder() {
        DungeonLog log = new DungeonLog();
        // Every step is due at once under J5; the tutorial still names one at
        // a time, in enum order.
        check(StationTutorial.next(log, PLAYER) == StationTutorial.Step.SALVAGE,
                "the salvage bench comes first");
        StationTutorial.markDone(log, PLAYER, StationTutorial.Step.SALVAGE);
        check(StationTutorial.next(log, PLAYER) == StationTutorial.Step.REROLL,
                "then the enchanting table");
        StationTutorial.markDone(log, PLAYER, StationTutorial.Step.REROLL);
        check(StationTutorial.next(log, PLAYER) == StationTutorial.Step.VENDOR,
                "then the lectern's librarian");
    }

    private static void testDoneIsPermanent() {
        DungeonLog log = new DungeonLog();
        for (StationTutorial.Step step : StationTutorial.Step.values()) {
            StationTutorial.markDone(log, PLAYER, step);
        }
        check(StationTutorial.next(log, PLAYER) == null, "a finished tutorial never nags");
    }

    private static void testBlockName() {
        check("enchanting table".equals(StationTutorial.blockName("minecraft:enchanting_table")),
                "ids read as words");
    }

    /** The scan checks for the blocks the plan's three stations actually are. */
    private static void testBlockIds() {
        check(StationTutorial.Step.SALVAGE.blockId(), "minecraft:grindstone",
                "the salvage bench is the grindstone");
        check(StationTutorial.Step.REROLL.blockId(), "minecraft:enchanting_table",
                "the reroll moved to the enchanting table");
        check(StationTutorial.Step.VENDOR.blockId(), "minecraft:lectern",
                "the home vendor is the lectern");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
