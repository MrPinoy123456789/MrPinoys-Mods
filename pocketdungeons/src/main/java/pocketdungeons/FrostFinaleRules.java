package pocketdungeons;

/**
 * The pure rules of the Frostworks finale (roadmap T33, boss workshop 2026-10-11 row 11): while the wave is up,
 * a member in The Big Freeze gains vanilla freeze ticks every tick; a member near a lit brazier thaws fast;
 * and the cold never kills. No Minecraft imports, so {@code FrostFinaleRulesTest} runs with plain
 * {@code javac}. The rate and the radius are config knobs ({@code frostColdTicksPerSecond},
 * {@code frostBrazierRadius}); everything else is a named constant here.
 *
 * <p>Why a meter of our own: vanilla takes 2 freeze ticks off every tick a living thing is not in powder snow,
 * so a value written once a second would saw-tooth by 40. The script keeps a decimal meter per member, moves it
 * every tick and writes the whole part to {@code Entity.setTicksFrozen}.
 */
final class FrostFinaleRules {

    private FrostFinaleRules() {}

    /** Vanilla {@code Entity.BASE_TICKS_REQUIRED_TO_FREEZE}: at this many ticks a member is fully frozen and takes damage. */
    static final int FULL_FREEZE_TICKS = 140;

    /**
     * Freeze ticks vanilla takes off every tick a member is out of powder snow. It runs in the entity tick,
     * before the damage check and before our server tick listener, so a value we write is 2 lower when vanilla
     * looks at it.
     */
    static final int VANILLA_DECAY_PER_TICK = 2;

    /** The most a healthy member holds: after vanilla's decay it is still 140, fully frozen, so the cold can bite. */
    static final int FULL_CEILING_TICKS = FULL_FREEZE_TICKS + VANILLA_DECAY_PER_TICK;

    /**
     * The most a member at or under {@link #HEALTH_FLOOR} may hold. Five under the freeze line, so even a tick of
     * powder snow (which adds one) cannot reach it before our tick pulls the value back down.
     */
    static final int SAFE_CEILING_TICKS = FULL_FREEZE_TICKS - 5;

    /**
     * A member at or under this health (2 hearts) is never fully frozen. Vanilla deals 1 damage every 2 seconds
     * to a fully frozen member, so a member above the floor ends the hit with at least 1 health left.
     */
    static final double HEALTH_FLOOR = 2.0;

    /** Freeze ticks a member in warmth loses each tick: 140 to 0 in 35 ticks, a clear and quick thaw. */
    static final double THAW_TICKS_PER_TICK = 4.0;

    /** Ticks between the wind tell and the first freeze tick: 3 seconds, over the 2 second fairness minimum. */
    static final int TELL_TICKS = 60;

    /** The shortest tell the finale-script skill allows (2 seconds). */
    static final int MIN_TELL_TICKS = 40;

    /** A member at this many freeze ticks (a seventh of a full freeze) is told once to find a fire. */
    static final int WARN_TICKS = 20;

    /** Below this the warning is armed again, so it shows once per freeze, not once per tick. */
    static final int WARN_RESET_TICKS = 5;

    /** Braziers kept from a room scan. A room is built with four to six; the cap bounds the per tick check. */
    static final int MAX_BRAZIERS = 12;

    /** Freeze ticks gained per game tick for a knob of {@code perSecond} ticks per second. */
    static double risePerTick(int perSecond) {
        return Math.max(0, perSecond) / 20.0;
    }

    /** The most freeze ticks a member of this health may hold. */
    static int ceilingFor(double health) {
        return health > HEALTH_FLOOR ? FULL_CEILING_TICKS : SAFE_CEILING_TICKS;
    }

    /**
     * The meter after one tick. Warm: it falls by {@link #THAW_TICKS_PER_TICK}. Cold: it rises by
     * {@code rise}. Either way it stays between 0 and the health ceiling, and a member already over the ceiling
     * (a health drop, powder snow) is pulled down to it at once.
     */
    static double next(double meter, boolean warm, double rise, double health) {
        double moved = warm ? meter - THAW_TICKS_PER_TICK : meter + Math.max(0.0, rise);
        return Math.max(0.0, Math.min(ceilingFor(health), moved));
    }

    /** The whole number written to the entity for a meter value; never above the ceiling. */
    static int ticksToWrite(double meter, double health) {
        return (int) Math.max(0.0, Math.min(ceilingFor(health), Math.floor(meter)));
    }

    /** Whether a placed room id is The Big Freeze ({@code big_freeze}, with or without the namespace). */
    static boolean isBigFreezeRoom(String roomId) {
        return roomId != null && (roomId.equals("big_freeze") || roomId.endsWith(":big_freeze"));
    }

    /** In the last phase the warm spots shrink to this percent of the radius knob. */
    static final int SHRUNK_RADIUS_PERCENT = 50;

    /** The smallest a shrunk warm spot gets (never wider than the knob itself): a body length from the fire. */
    static final double MIN_SHRUNK_RADIUS = 2.0;

    /** The radius a brazier warms: the knob, or its shrunk share in the last phase (never below the minimum, never above the knob). */
    static double warmRadius(double base, boolean shrunk) {
        if (!shrunk) {
            return base;
        }
        return Math.min(base, Math.max(MIN_SHRUNK_RADIUS, base * SHRUNK_RADIUS_PERCENT / 100.0));
    }

    /** Whether a point {@code (dx, dy, dz)} from a brazier is within {@code radius} blocks of it. */
    static boolean withinRadius(double dx, double dy, double dz, double radius) {
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /**
     * Whether the cold runs at all. A room with no lit brazier would freeze the party with no way to thaw, so
     * the cold stays off (the fight is then the plain data wave) and the script logs it.
     */
    static boolean coldApplies(int braziers, int ticksPerSecond) {
        return braziers > 0 && ticksPerSecond > 0;
    }

    /** Whether the tell is over and the cold may start: {@code coldAt} is the tick it was scheduled for. */
    static boolean coldStarted(long now, long coldAt) {
        return coldAt >= 0 && now >= coldAt;
    }

    /** Whether the one time "find a fire" line is due for a member at this meter, given it was not shown yet. */
    static boolean warnDue(double meter, boolean alreadyWarned) {
        return !alreadyWarned && meter >= WARN_TICKS;
    }

    /** Whether the warning is armed again for a member at this meter. */
    static boolean warnReset(double meter) {
        return meter < WARN_RESET_TICKS;
    }
}
