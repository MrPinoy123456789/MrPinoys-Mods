package pocketdungeons;

/**
 * The pure rules of haunts (docs/haunts-draft-2026-10-11.md), with no Minecraft imports so
 * {@code HauntRulesTest} runs with plain {@code javac}: which stage a floor gets, the look cone arithmetic, the
 * pressure, the strike damage and the one decision a stalker makes each tick.
 *
 * <p>Owner decision 2026-10-11: the ladder is counted by floor. Floor 1 is a sighting (the haunt stalks, never
 * attacks, and vanishes), floor 2 a near miss (it reaches a member and misses), floor 3 onwards a strike. A
 * dungeon of two floors therefore never strikes, whatever the knobs say.
 */
final class HauntRules {

    /** How the haunt treats a member it reaches, by floor. */
    enum Stage { SIGHTING, NEAR_MISS, STRIKE }

    /** What a stalker does this tick. */
    enum Move { FREEZE, ADVANCE, VANISH, NEAR_MISS, STRIKE }

    /** The line under the floor title on a haunted floor. */
    static final String TITLE_LINE = "Something walks here.";

    /** Blocks within which a haunt is close enough to act on a member. */
    static final double REACH = 2.2;

    /** The first floor a strike may land on, whatever the knobs say: a two floor dungeon never strikes. */
    static final int FIRST_STRIKE_FLOOR = 3;

    private HauntRules() {}

    // ---- the ladder -------------------------------------------------------------------------------

    /**
     * The stage of {@code floor} (1 is a dungeon's first floor). Below {@code nearMissFloor} a sighting, below
     * {@code strikeFloor} a near miss, from there on a strike. The strike floor never drops below
     * {@link #FIRST_STRIKE_FLOOR} nor to or under the near miss floor.
     */
    static Stage stageFor(int floor, int nearMissFloor, int strikeFloor) {
        int strike = Math.max(Math.max(strikeFloor, FIRST_STRIKE_FLOOR), nearMissFloor + 1);
        if (floor < nearMissFloor) {
            return Stage.SIGHTING;
        }
        return floor < strike ? Stage.NEAR_MISS : Stage.STRIKE;
    }

    // ---- looking ----------------------------------------------------------------------------------

    /**
     * Whether a viewer at {@code eye} looking along {@code look} has {@code target} inside a cone of
     * {@code halfAngleDegrees}: the dot product of the unit look vector and the unit direction to the target is
     * at least the cosine of the half angle. A target on the viewer counts as seen. Line of sight is the
     * caller's business.
     */
    static boolean inCone(double eyeX, double eyeY, double eyeZ, double lookX, double lookY, double lookZ,
                          double targetX, double targetY, double targetZ, double halfAngleDegrees) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double toLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double lookLength = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        if (toLength < 1.0e-6) {
            return true;
        }
        if (lookLength < 1.0e-6) {
            return false;
        }
        double dot = (lookX * dx + lookY * dy + lookZ * dz) / (lookLength * toLength);
        return dot >= Math.cos(Math.toRadians(halfAngleDegrees));
    }

    // ---- pressure ---------------------------------------------------------------------------------

    /** The pressure after {@code seconds} on the floor at {@code perSecond}. */
    static double pressure(double seconds, double perSecond) {
        return Math.max(0.0, seconds) * Math.max(0.0, perSecond);
    }

    /** Whether the pressure has reached the wake threshold. */
    static boolean wakes(double pressure, double threshold) {
        return pressure >= threshold;
    }

    // ---- the strike -------------------------------------------------------------------------------

    /** The damage a strike deals to a player on {@code health}: the knob, but never enough to kill. */
    static float strikeDamage(double configured, float health) {
        return (float) Math.max(0.0, Math.min(configured, health - 1.0f));
    }

    /** Whether the tell has run long enough to act: at least {@code leadTicks} since it began. */
    static boolean tellDone(long now, long tellStart, long leadTicks) {
        return tellStart >= 0 && now - tellStart >= leadTicks;
    }

    // ---- the stalker's decision ----------------------------------------------------------------------

    /**
     * What the Counted does this tick. It moves only while unseen; a member who looks at it holds it still. At
     * the approach range of a sighting it vanishes whether seen or not. At {@link #REACH} it acts (a near miss
     * or a strike by stage) only when unseen and only once the tell has run its lead time; until then it holds.
     */
    static Move decide(Stage stage, boolean seen, double distance, double approachRange, boolean tellDone) {
        if (stage == Stage.SIGHTING && distance <= approachRange) {
            return Move.VANISH;
        }
        if (seen) {
            return Move.FREEZE;
        }
        if (stage != Stage.SIGHTING && distance <= REACH) {
            if (!tellDone) {
                return Move.FREEZE;
            }
            return stage == Stage.STRIKE ? Move.STRIKE : Move.NEAR_MISS;
        }
        return Move.ADVANCE;
    }
}
