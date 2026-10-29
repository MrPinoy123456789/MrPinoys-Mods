package pocketdungeons;

/** Roadmap T24: the creeping brick stage clock, the caps, the seed picks and which rooms grow bricks. */
public class CreepingRulesTest {

    public static void main(String[] args) {
        // The clock: 100 / 100 / 60 ticks.
        long m = 100;
        long s = 100;
        long i = 60;
        eq(CreepingRules.stageAt(0, m, s, i), CreepingRules.MOSSY);
        eq(CreepingRules.stageAt(99, m, s, i), CreepingRules.MOSSY);
        eq(CreepingRules.stageAt(100, m, s, i), CreepingRules.STONE);
        eq(CreepingRules.stageAt(199, m, s, i), CreepingRules.STONE);
        eq(CreepingRules.stageAt(200, m, s, i), CreepingRules.INFESTED);
        eq(CreepingRules.stageAt(259, m, s, i), CreepingRules.INFESTED);
        eq(CreepingRules.stageAt(260, m, s, i), CreepingRules.BURST);
        eq(CreepingRules.stageAt(100000, m, s, i), CreepingRules.BURST);
        eq(CreepingRules.lifeTicks(m, s, i), 260);

        // Caps: the tighter of the room and the floor wins, and never below zero.
        eq(CreepingRules.slots(0, 4, 0, 10), 4);
        eq(CreepingRules.slots(3, 4, 0, 10), 1);
        eq(CreepingRules.slots(0, 4, 9, 10), 1);
        eq(CreepingRules.slots(4, 4, 0, 10), 0);
        eq(CreepingRules.slots(0, 4, 10, 10), 0);
        eq(CreepingRules.slots(5, 4, 12, 10), 0);

        // Door lanes stay clear; the middle and the corners are free.
        check(CreepingRules.inDoorLane(7, 1) && CreepingRules.inDoorLane(8, 14), "north and south lanes");
        check(CreepingRules.inDoorLane(1, 7) && CreepingRules.inDoorLane(14, 8), "west and east lanes");
        check(!CreepingRules.inDoorLane(7, 7) && !CreepingRules.inDoorLane(2, 2) && !CreepingRules.inDoorLane(7, 5),
                "the middle is free");

        // Which rooms grow bricks: Chewed Corridors and Silverfish Nests rooms in an Infestation run, nothing else.
        check(CreepingRules.seeds("pocketdungeons:burrow_tunnel", "infestation"), "burrow tunnel");
        check(CreepingRules.seeds("gnawed_vein", "infestation"), "bare id, gnawed vein");
        check(CreepingRules.seeds("pocketdungeons:slime_pit", "infestation"), "slime pit");
        check(!CreepingRules.seeds("pocketdungeons:infested_wall", "infestation"), "the Lush Caves wall is left alone");
        check(!CreepingRules.seeds("pocketdungeons:burrow_tunnel", "mineshaft"), "other themes grow none");
        check(!CreepingRules.seeds("pocketdungeons:queens_nest", "infestation"), "the boss room is the finale's");
        check(!CreepingRules.seeds(null, "infestation") && !CreepingRules.seeds("x", null), "nulls");

        // Seed picks are deterministic, spread out, and bounded.
        int[] xs = new int[100];
        int[] zs = new int[100];
        for (int n = 0; n < 100; n++) {
            xs[n] = 1 + n % 10;
            zs[n] = 1 + n / 10;
        }
        int[] a = CreepingRules.pickSpread(42L, xs, zs, 3, CreepingRules.SEED_SPACING);
        int[] b = CreepingRules.pickSpread(42L, xs, zs, 3, CreepingRules.SEED_SPACING);
        eq(a.length, 3);
        check(java.util.Arrays.equals(a, b), "the same seed picks the same spots");
        for (int p = 0; p < a.length; p++) {
            for (int q = p + 1; q < a.length; q++) {
                check(Math.max(Math.abs(xs[a[p]] - xs[a[q]]), Math.abs(zs[a[p]] - zs[a[q]]))
                        >= CreepingRules.SEED_SPACING, "seeds keep their spacing");
            }
        }
        check(!java.util.Arrays.equals(a, CreepingRules.pickSpread(43L, xs, zs, 3, CreepingRules.SEED_SPACING)),
                "another seed picks other spots");
        eq(CreepingRules.pickSpread(1L, new int[]{1, 2}, new int[]{1, 1}, 5, 3).length, 1); // two adjacent spots: only one fits
        eq(CreepingRules.pickSpread(1L, new int[0], new int[0], 3, 3).length, 0);

        // The live tell (PD-222): the sparkle cadence tightens with the stage so the countdown reads,
        // and every period is a positive multiple of the 5-tick poll so a beat is never skipped.
        check(CreepingRules.tellTicks(CreepingRules.MOSSY) > CreepingRules.tellTicks(CreepingRules.STONE),
                "mossy shimmers slower than stone");
        check(CreepingRules.tellTicks(CreepingRules.STONE) > CreepingRules.tellTicks(CreepingRules.INFESTED),
                "stone pulses slower than infested");
        for (int stage : new int[]{CreepingRules.MOSSY, CreepingRules.STONE, CreepingRules.INFESTED}) {
            check(CreepingRules.tellTicks(stage) > 0 && CreepingRules.tellTicks(stage) % 5 == 0,
                    "tell period is a positive multiple of the poll, stage " + stage);
        }

        // Seed start delays: deterministic, staggered by index, jitter under the bound.
        for (int n = 0; n < 4; n++) {
            long d = CreepingRules.startDelay(7L, n);
            eq(d, CreepingRules.startDelay(7L, n));
            check(d >= (long) n * CreepingRules.SEED_GAP_TICKS
                    && d < (long) n * CreepingRules.SEED_GAP_TICKS + CreepingRules.SEED_JITTER_TICKS, "delay band " + n);
        }

        System.out.println("CreepingRulesTest passed");
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
