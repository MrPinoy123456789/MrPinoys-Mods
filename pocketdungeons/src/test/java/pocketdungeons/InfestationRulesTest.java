package pocketdungeons;

import pocketdungeons.InfestationRules.Phase;
import pocketdungeons.InfestationRules.Timing;

/** Roadmap T24: the giant silverfish's phase schedule, its tells, brick counts and the silverfish cap. */
public class InfestationRulesTest {

    public static void main(String[] args) {
        // Every tell is at least 2 seconds, however the knobs are set.
        Timing t = InfestationRules.timing(25, 3, 2, 40, 2);
        eq(t.surface(), 500);
        eq(t.burrowTell(), 60);
        eq(t.addsTell(), 40);
        eq(t.addsMax(), 800);
        eq(t.emergeTell(), 40);
        Timing low = InfestationRules.timing(1, 0, 0, 1, 0);
        check(low.burrowTell() >= 40 && low.addsTell() >= 40 && low.emergeTell() >= 40, "tells never under 2 seconds");

        // One full cycle.
        Phase p = Phase.SURFACE;
        p = step(p, 499, t, false, Phase.SURFACE);
        p = step(p, 500, t, false, Phase.BURROW_TELL);
        p = step(p, 59, t, false, Phase.BURROW_TELL);
        p = step(p, 60, t, false, Phase.ADDS_TELL);
        p = step(p, 39, t, false, Phase.ADDS_TELL);
        p = step(p, 40, t, false, Phase.ADDS);
        p = step(p, 799, t, false, Phase.ADDS);
        // The adds phase ends early when every brick is gone.
        eq(InfestationRules.next(Phase.ADDS, 10, t, true), Phase.EMERGE_TELL);
        // Or when its timer runs out.
        p = step(p, 800, t, false, Phase.EMERGE_TELL);
        p = step(p, 39, t, false, Phase.EMERGE_TELL);
        p = step(p, 40, t, false, Phase.SURFACE);
        // And it repeats.
        step(p, 500, t, false, Phase.BURROW_TELL);

        // Only the surface and the first tell leave the boss reachable.
        check(!Phase.SURFACE.burrowed() && !Phase.BURROW_TELL.burrowed(), "she can be hit until she burrows");
        check(Phase.ADDS_TELL.burrowed() && Phase.ADDS.burrowed() && Phase.EMERGE_TELL.burrowed(), "burrowed phases");
        // A tell always sits before each strike: burrow tell before the burrow, adds tell before the adds.
        eq(Phase.SURFACE.ordinal() + 1, Phase.BURROW_TELL.ordinal());
        eq(Phase.ADDS_TELL.ordinal() + 1, Phase.ADDS.ordinal());

        // Bricks for a party.
        eq(InfestationRules.bricksFor(1, 6, 2), 6);
        eq(InfestationRules.bricksFor(4, 6, 2), 12);
        eq(InfestationRules.bricksFor(0, 6, 2), 6);
        eq(InfestationRules.bricksFor(1, 0, 0), 1);

        // Planting respects the arena, the floor and what is owed.
        eq(InfestationRules.plantNow(6, 0, 6, 0, 10), 6);
        eq(InfestationRules.plantNow(6, 4, 6, 4, 10), 2);
        eq(InfestationRules.plantNow(6, 0, 6, 9, 10), 1);
        eq(InfestationRules.plantNow(1, 0, 6, 0, 10), 1);
        eq(InfestationRules.plantNow(0, 0, 6, 0, 10), 0);
        eq(InfestationRules.plantNow(6, 6, 6, 6, 10), 0);

        // The silverfish cap: ripe bricks wait while it is full.
        eq(InfestationRules.releaseBudget(0, 10), 10);
        eq(InfestationRules.releaseBudget(7, 10), 3);
        eq(InfestationRules.releaseBudget(10, 10), 0);
        eq(InfestationRules.releaseBudget(14, 10), 0);

        // The adds phase is done when all are planted and none grows.
        check(InfestationRules.addsDone(6, 6, 0), "done");
        check(!InfestationRules.addsDone(6, 6, 1), "a brick still grows");
        check(!InfestationRules.addsDone(3, 6, 0), "bricks still owed");

        System.out.println("InfestationRulesTest passed");
    }

    private static Phase step(Phase from, long elapsed, Timing t, boolean addsDone, Phase expected) {
        Phase next = InfestationRules.next(from, elapsed, t, addsDone);
        if (next != expected) {
            throw new AssertionError(from + " at " + elapsed + " should be " + expected + " but was " + next);
        }
        return next;
    }

    private static void eq(Object actual, Object expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void eq(long actual, long expected) {
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
