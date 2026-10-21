package pocketdungeons;

/**
 * The numbers of the Endless Mine guardians (roadmap T41, decision 2026-10-11): how the uncapped
 * {@code hardness} dial becomes counts, size and tell speed for the Act 1 guardians, and the two
 * fairness floors that dial can never cross. No Minecraft imports, so {@code MineGuardianRulesTest}
 * runs with plain {@code javac}. The guardians in {@link MineGuardianQueen}, {@link MineGuardianPack}
 * and {@link MineGuardianCoffins} do the world work.
 *
 * <p>The floors: a tell is never shorter than {@link #TELL_FLOOR_TICKS} (two seconds), and a guardian
 * never fields more than {@code mineGuardianMobCap} mobs, counted as everything it has standing plus
 * everything still waiting to rise.
 */
final class MineGuardianRules {

    private MineGuardianRules() {}

    /** A tell may never run shorter than this, whatever the hardness: two seconds. */
    static final int TELL_FLOOR_TICKS = 40;

    /** The base tell of the young queen's burrow and of one coffin's cracking lid: three seconds. */
    static final int TELL_BASE_TICKS = 60;

    /** Ticks a tell loses per full point of hardness above the first, down to the floor. */
    static final int TELL_STEP_TICKS = 20;

    /** Ticks after one coffin opens before the next may start to crack. */
    static final int COFFIN_GAP_TICKS = 40;

    /** The most coffins a wave can stand in the terminal cell; each holds one sleeper. */
    static final int MAX_COFFINS = 6;

    /** The most creeping bricks the young queen plants around herself. */
    static final int MAX_BRICKS = 10;

    /** The most wolves behind the packmaster. */
    static final int MAX_WOLVES = 8;

    /** The young queen's SCALE at the top of the dial; the real Queen is {@code infestationBossScale}. */
    static final double QUEEN_MAX_SCALE = 2.6;

    /**
     * A tell of {@code baseTicks} at {@code hardness}: shorter as hardness climbs, never under
     * {@link #TELL_FLOOR_TICKS}.
     */
    static int tellTicks(int baseTicks, double hardness) {
        long cut = Math.round(TELL_STEP_TICKS * Math.max(0.0, hardness - 1.0));
        return (int) Math.max(TELL_FLOOR_TICKS, baseTicks - cut);
    }

    /** The creeping bricks the young queen plants at {@code hardness}: 2 at 1.0, one more per point. */
    static int bricksFor(double hardness) {
        return clamp(1 + (int) Math.round(hardness), 1, MAX_BRICKS);
    }

    /** The wolves behind the packmaster at {@code hardness}: 3 at 1.0, one more per point. */
    static int wolvesFor(double hardness) {
        return clamp(2 + (int) Math.round(hardness), 1, MAX_WOLVES);
    }

    /** The coffins a wave stands at {@code hardness}, one sleeper each: 2 at 1.0, one more per point. */
    static int coffinsFor(double hardness) {
        return clamp(1 + (int) Math.round(hardness), 1, MAX_COFFINS);
    }

    /** The young queen's SCALE: 1.5 at hardness 1.0, a tenth per point, under {@link #QUEEN_MAX_SCALE}. */
    static double queenScale(double hardness) {
        return Math.min(QUEEN_MAX_SCALE, 1.5 + 0.1 * Math.max(0.0, hardness - 1.0));
    }

    /** The tiles the young queen's emergence strike covers: a small circle for a small fight. */
    static final double EMERGE_RADIUS = 2.0;

    /** The most the young queen's emergence strike may hit for, however deep the mine runs. */
    static final float MAX_EMERGE_DAMAGE = 12.0f;

    /** The young queen's emergence strike at {@code hardness}: 4.0 at 1.0, two more per point, capped. */
    static float emergeDamage(double hardness) {
        return (float) Math.min(MAX_EMERGE_DAMAGE, 2.0 + 2.0 * hardness);
    }

    /** {@code wanted} cut back to the {@code left} slots the mob cap leaves. */
    static int capped(int wanted, int left) {
        return Math.max(0, Math.min(wanted, left));
    }

    // ---- the Act 2 guardians (roadmap T42) -------------------------------------------------------------

    /** The most vents the Foreman stands over. */
    static final int MAX_VENTS = 8;

    /** The most strays the frost guardian fields. */
    static final int MAX_STRAYS = 3;

    /** Sculk sensors stood in the sentry's cell for the listening look; decor only, the count is fixed. */
    static final int SENSORS = 4;

    /** Braziers stood in the frost cell: the cold's relief, fixed so hardness cannot remove the way out. */
    static final int BRAZIERS = 3;

    /** The least the sentry's meter ceiling may fall to: the fight can always be read. */
    static final int SENTRY_MIN_CEILING = 3;

    /**
     * The Deep Sentry's meter ceiling at {@code hardness}: {@code baseCeiling} at 1.0, a quarter faster
     * to fill per point, never under a quarter of the base and never under {@link #SENTRY_MIN_CEILING}.
     */
    static int sentryCeiling(double hardness, int baseCeiling) {
        int scaled = (int) Math.round(baseCeiling / (1.0 + 0.25 * Math.max(0.0, hardness - 1.0)));
        return Math.max(SENTRY_MIN_CEILING, Math.max(baseCeiling / 4, scaled));
    }

    /** The floor grates the Foreman vents through: 4 at hardness 1.0, one more per point, capped. */
    static int ventsFor(double hardness) {
        return clamp(3 + (int) Math.round(hardness), 1, MAX_VENTS);
    }

    /**
     * The Foreman's vent cycle at {@code hardness}: {@code baseTicks} at 1.0, a second faster per point,
     * never under {@link CopperClockRules#MIN_PERIOD_TICKS} so the tell plus the burn always fit.
     */
    static int ventPeriodTicks(int baseTicks, double hardness) {
        long cut = Math.round(20.0 * Math.max(0.0, hardness - 1.0));
        return (int) Math.max(CopperClockRules.MIN_PERIOD_TICKS, baseTicks - cut);
    }

    /** The strays in the frost wave: 1 at hardness 1.0, one more about every two points, capped. */
    static int straysFor(double hardness) {
        return clamp(1 + (int) Math.round((hardness - 1.0) / 2.0), 1, MAX_STRAYS);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
