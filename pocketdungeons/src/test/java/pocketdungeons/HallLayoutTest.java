package pocketdungeons;

import java.util.List;
import java.util.Set;

/** Q1: the Astrolabe Room's door row, door states, start marker and act cycle. */
public class HallLayoutTest {

    public static void main(String[] args) {
        // The pattern from the owner's ruling: OXOXOXOOXOXOXO for a full act.
        eq(HallLayout.pattern(6, 0), "OXOXOXOOXOXOXO");
        eq(HallLayout.pattern(5, 0), "OXOXOXOOXOXOOO");
        eq(HallLayout.pattern(1, 0), "OOOOOXOOOOOOOO");
        eq(HallLayout.pattern(2, 0), "OOOOOXOOXOOOOO");
        eq(HallLayout.pattern(3, 0), "OOOXOXOOXOOOOO");
        eq(HallLayout.pattern(0, 0), "OOOOOOOOOOOOOO");
        // The doorway slot at 7 and 8 is never a door.
        for (int n = 0; n <= 6; n++) {
            for (int s = 0; s <= 2; s++) {
                for (int along : HallLayout.alongs(n, s)) {
                    if (along == 7 || along == 8) {
                        throw new AssertionError("a door in the doorway slot for " + n + "," + s);
                    }
                    if (along < 1 || along > 14) {
                        throw new AssertionError("outside the wall: " + along);
                    }
                }
                eq(HallLayout.alongs(n, s).length, n + s);
            }
        }
        // Act doors read left to right; specials follow, at the ends.
        eq(java.util.Arrays.toString(HallLayout.alongs(3, 0)), "[4, 6, 9]");
        eq(java.util.Arrays.toString(HallLayout.alongs(5, 1)), "[2, 4, 6, 9, 11, 14]");
        eq(java.util.Arrays.toString(HallLayout.alongs(2, 2)), "[6, 9, 14, 1]");
        // Never more than six act doors or two specials.
        eq(HallLayout.alongs(9, 9).length, 8);

        // Door states in Act 2 of the live data: Copper Works 2, Deepslate 3, Frostworks 4, Cow Pits 2, Ancient City capstone.
        List<HallLayout.Entry> act = HallLayout.ordered(List.of(
                new HallLayout.Entry("pocketdungeons:ancient_city", 5, true, false, false),
                new HallLayout.Entry("pocketdungeons:frostworks", 4, false, false, false),
                new HallLayout.Entry("pocketdungeons:cow_pits", 2, false, true, false),
                new HallLayout.Entry("pocketdungeons:deepslate", 3, false, false, false),
                new HallLayout.Entry("pocketdungeons:copper_works", 2, false, false, false)));
        eq(act.get(0).id(), "pocketdungeons:copper_works");
        eq(act.get(1).id(), "pocketdungeons:cow_pits");
        eq(act.get(4).id(), "pocketdungeons:ancient_city");

        List<HallLayout.Status> at3 = HallLayout.statuses(act, 3);
        eq(at3.get(0).state(), HallLayout.State.OPEN);
        eq(at3.get(1).state(), HallLayout.State.FINISHED);
        eq(at3.get(2).state(), HallLayout.State.OPEN);
        eq(at3.get(3).state(), HallLayout.State.COMPASS_LOCKED);
        eq(at3.get(4).state(), HallLayout.State.CAPSTONE_LOCKED);
        eq(at3.get(0).startHere(), true);
        eq(at3.get(2).startHere(), false);
        check(at3.get(3).locked() && at3.get(4).locked() && !at3.get(0).locked(), "locked flags");

        // Everything but the capstone finished: the capstone is ready and is where to start.
        List<HallLayout.Entry> done = List.of(
                new HallLayout.Entry("a", 2, false, true, false),
                new HallLayout.Entry("b", 3, false, true, false),
                new HallLayout.Entry("cap", 5, true, false, true));
        List<HallLayout.Status> ready = HallLayout.statuses(done, 6);
        eq(ready.get(2).state(), HallLayout.State.OPEN);
        check(ready.get(2).capstoneReady() && ready.get(2).startHere(), "the ready capstone is the place to start");
        eq(ready.get(0).state(), HallLayout.State.FINISHED);
        check(!ready.get(0).startHere(), "a finished dungeon is not the start");
        // The same act with the compass short of the capstone: locked by compass, no start.
        List<HallLayout.Status> short5 = HallLayout.statuses(done, 4);
        eq(short5.get(2).state(), HallLayout.State.COMPASS_LOCKED);
        eq(HallLayout.statuses(List.of(), 3).size(), 0);

        // Act cycling over the open acts, wrapping both ways.
        eq(HallLayout.nextAct(Set.of(1, 2, 3), 1, false), 2);
        eq(HallLayout.nextAct(Set.of(1, 2, 3), 3, false), 1);
        eq(HallLayout.nextAct(Set.of(1, 2, 3), 1, true), 3);
        eq(HallLayout.nextAct(Set.of(1), 1, false), 1);
        eq(HallLayout.nextAct(Set.of(2, 4), 1, false), 2);
        eq(HallLayout.nextAct(Set.of(), 1, false), 1);

        // The room opens on the highest open act with something left to do.
        eq(HallLayout.openingAct(Set.of(1, 2, 3), a -> a <= 2), 2);
        eq(HallLayout.openingAct(Set.of(1, 2), a -> false), 2);
        eq(HallLayout.openingAct(Set.of(), a -> true), 1);
        System.out.println("HallLayoutTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object actual, Object expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
