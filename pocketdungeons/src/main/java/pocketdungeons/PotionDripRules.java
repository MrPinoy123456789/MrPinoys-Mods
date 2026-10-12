package pocketdungeons;

/**
 * The pure rules of the Lush Caves potion drip (roadmap T23; boss workshop 2026-10-11, decision 2b): from which
 * floor it drips, how often, what falls, and how fast a potion must descend to take about two seconds. No
 * Minecraft imports, so {@code PotionDripRulesTest} runs with plain {@code javac}.
 */
final class PotionDripRules {

    private PotionDripRules() {}

    /** What can fall. The first four are the harmful pool, {@link #HARMING} joins it at a high enough level. */
    enum Brew {
        POISON(true), SLOWNESS(true), WEAKNESS(true), NAUSEA(true), HARMING(true),
        REGENERATION(false), SPEED(false), JUMP_BOOST(false);

        private final boolean harmful;

        Brew(boolean harmful) {
            this.harmful = harmful;
        }

        boolean harmful() {
            return harmful;
        }
    }

    /** The always available harmful brews, in pick order. */
    private static final Brew[] HARMFUL = {Brew.POISON, Brew.SLOWNESS, Brew.WEAKNESS, Brew.NAUSEA};
    private static final Brew[] GOOD = {Brew.REGENERATION, Brew.SPEED, Brew.JUMP_BOOST};

    /** Drag a thrown projectile keeps per tick; used to turn a distance and a time into a speed. */
    static final double DRAG = 0.99;

    /** A harming brew is only thrown at a player with at least this much health (half hearts), so it is never lethal on its own. */
    static final float HARMING_MIN_HEALTH = 14.0f;

    /** Whether the floor of the run (1 based) is one the brews drip on. */
    static boolean drips(int floorNumber, int startFloor) {
        return floorNumber >= Math.max(1, startFloor);
    }

    /**
     * How fast brews fall, as a percent of the base rate: 100 on the first dripping floor, plus
     * {@code rampPercent} for each later floor, times {@code bossPercent} / 100 during the boss fight. Zero
     * before the first dripping floor.
     */
    static int ratePercent(int floorNumber, int startFloor, int rampPercent, boolean boss, int bossPercent) {
        if (!drips(floorNumber, startFloor)) {
            return 0;
        }
        int later = floorNumber - Math.max(1, startFloor);
        long rate = 100L + (long) Math.max(0, rampPercent) * later;
        if (boss) {
            rate = rate * Math.max(100, bossPercent) / 100;
        }
        return (int) Math.min(rate, 100_000L);
    }

    /** Ticks between two brews for one player at this rate, never under {@code minSeconds}. Zero rate: never ({@code -1}). */
    static int intervalTicks(int baseSeconds, int ratePercent, int minSeconds) {
        if (ratePercent <= 0) {
            return -1;
        }
        long ticks = Math.max(1, baseSeconds) * 20L * 100L / ratePercent;
        return (int) Math.max(ticks, Math.max(1, minSeconds) * 20L);
    }

    /** {@code ticks} varied by up to {@code jitterPercent} either way; {@code roll} is in [0, 1). */
    static int jittered(int ticks, int jitterPercent, double roll) {
        int j = Math.max(0, Math.min(90, jitterPercent));
        double factor = (100 - j + 2.0 * j * Math.max(0.0, Math.min(0.999999, roll))) / 100.0;
        return Math.max(1, (int) Math.round(ticks * factor));
    }

    /**
     * Whether a harming brew may fall: the floor level reaches {@code minLevel} ({@code 0} means never) and the
     * player has the health to take it.
     */
    static boolean harmingAllowed(int level, int minLevel, float health) {
        return minLevel > 0 && level >= minLevel && health >= HARMING_MIN_HEALTH;
    }

    /**
     * The brew for a roll: harmful with chance {@code harmfulPercent}, then one of that pool by {@code pick}
     * ({@code kindRoll} and {@code pick} are in [0, 1)).
     */
    static Brew pick(int harmfulPercent, boolean harmingAllowed, double kindRoll, double pick) {
        double p = Math.max(0.0, Math.min(0.999999, pick));
        if (kindRoll * 100.0 < harmfulPercent) {
            int n = HARMFUL.length + (harmingAllowed ? 1 : 0);
            int i = (int) (p * n);
            return i < HARMFUL.length ? HARMFUL[i] : Brew.HARMING;
        }
        return GOOD[(int) (p * GOOD.length)];
    }

    /**
     * The downward speed (blocks per tick) a drag-slowed projectile with no gravity needs to cover
     * {@code distance} blocks in {@code ticks} ticks.
     */
    static double fallSpeed(double distance, int ticks) {
        int t = Math.max(1, ticks);
        double travelled = (1.0 - Math.pow(DRAG, t)) / (1.0 - DRAG);
        return Math.max(0.0, distance) / travelled;
    }

    /** The effect length in ticks for a brew, from the base seconds knob. */
    static int durationTicks(Brew brew, int baseSeconds) {
        int seconds = Math.max(1, baseSeconds);
        return switch (brew) {
            case HARMING -> 1;
            case SPEED, JUMP_BOOST -> seconds * 2 * 20;
            case WEAKNESS -> seconds * 20 + seconds * 5;
            default -> seconds * 20;
        };
    }

    /** The colour of a brew's potion, as RGB. */
    static int colour(Brew brew) {
        return switch (brew) {
            case POISON -> 0x4E9331;
            case SLOWNESS -> 0x5A6C81;
            case WEAKNESS -> 0x484D48;
            case NAUSEA -> 0x551D4A;
            case HARMING -> 0xA9656A;
            case REGENERATION -> 0xCD5CAB;
            case SPEED -> 0x33EBFF;
            case JUMP_BOOST -> 0xFDFF84;
        };
    }
}
