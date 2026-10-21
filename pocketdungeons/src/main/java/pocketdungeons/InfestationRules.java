package pocketdungeons;

/**
 * The pure schedule of the Infestation finale (roadmap T24), no Minecraft imports so
 * {@code InfestationRulesTest} runs with plain javac. {@link InfestationFinale} applies it.
 *
 * <p>The cycle: the boss fights on the surface, rears up (tell), burrows, the floor cracks (tell), bricks
 * grow and burst into silverfish (the adds phase), the ground heaves (tell), the boss emerges. Every tell is
 * at least {@link #MIN_TELL_TICKS} (2 seconds) however the knobs are set.
 */
final class InfestationRules {

    enum Phase {
        /** The boss is up and fighting. */
        SURFACE,
        /** The boss rears up and dust rises; still hittable. The burrow follows. */
        BURROW_TELL,
        /** The boss is gone; the floor cracks before the first brick. */
        ADDS_TELL,
        /** Bricks are planted and burst into silverfish. */
        ADDS,
        /** Still underground; the ground heaves where the boss will rise. */
        EMERGE_TELL;

        /** Whether the boss is out of reach (invisible, invulnerable, underground) in this phase. */
        boolean burrowed() {
            return this == ADDS_TELL || this == ADDS || this == EMERGE_TELL;
        }
    }

    /** The shortest warning before a strike or a change of phase: 2 seconds. */
    static final long MIN_TELL_TICKS = 40;

    /** Phase lengths in ticks; {@code addsMax} is the longest the adds phase lasts. */
    record Timing(long surface, long burrowTell, long addsTell, long addsMax, long emergeTell) {}

    private InfestationRules() {}

    /** Builds the timing from the knobs in seconds; every tell is raised to {@link #MIN_TELL_TICKS}. */
    static Timing timing(int surfaceSeconds, int burrowTellSeconds, int addsTellSeconds, int addsMaxSeconds,
                         int emergeTellSeconds) {
        return new Timing(Math.max(20L, surfaceSeconds * 20L),
                Math.max(MIN_TELL_TICKS, burrowTellSeconds * 20L),
                Math.max(MIN_TELL_TICKS, addsTellSeconds * 20L),
                Math.max(20L, addsMaxSeconds * 20L),
                Math.max(MIN_TELL_TICKS, emergeTellSeconds * 20L));
    }

    /**
     * The phase after {@code phase} has run {@code elapsed} ticks. {@code addsDone} says every brick of the
     * phase was planted and none is left growing; it only matters in {@link Phase#ADDS}.
     */
    static Phase next(Phase phase, long elapsed, Timing t, boolean addsDone) {
        return switch (phase) {
            case SURFACE -> elapsed >= t.surface() ? Phase.BURROW_TELL : phase;
            case BURROW_TELL -> elapsed >= t.burrowTell() ? Phase.ADDS_TELL : phase;
            case ADDS_TELL -> elapsed >= t.addsTell() ? Phase.ADDS : phase;
            case ADDS -> addsDone || elapsed >= t.addsMax() ? Phase.EMERGE_TELL : phase;
            case EMERGE_TELL -> elapsed >= t.emergeTell() ? Phase.SURFACE : phase;
        };
    }

    /** Bricks one adds phase plants for a party: the base plus the extra for each member past the first. */
    static int bricksFor(int party, int perPhase, int perExtraMember) {
        return Math.max(1, perPhase + Math.max(0, party - 1) * perExtraMember);
    }

    /** How many bricks to plant now: the arena has room for, the floor has room for, and the phase still owes. */
    static int plantNow(int owed, int activeInArena, int arenaCap, int activeOnFloor, int floorCap) {
        return Math.min(Math.max(0, owed), CreepingRules.slots(activeInArena, arenaCap, activeOnFloor, floorCap));
    }

    /** How many bricks may burst now: the silverfish cap minus the silverfish alive, never negative. */
    static int releaseBudget(int aliveAdds, int cap) {
        return Math.max(0, cap - aliveAdds);
    }

    /** Whether the adds phase has nothing left to do: all planted and none growing. */
    static boolean addsDone(int planted, int total, int activeBricks) {
        return planted >= total && activeBricks == 0;
    }

    // ---- the emerge slam -----------------------------------------------------------------------------

    /**
     * The tiles the emergence strike covers, a circle of this radius in blocks around her rising spot.
     * Marked through the whole emerge tell by {@link AoETelegraph}, so a member who reads the ground and
     * steps off takes nothing.
     */
    static final double EMERGE_RADIUS = 3.0;

    /** The damage the emergence strike lands (her melee is {@code infestationBossDamage}). */
    static final float EMERGE_DAMAGE = 8.0f;
}
