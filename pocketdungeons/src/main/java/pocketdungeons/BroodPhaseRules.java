package pocketdungeons;

/**
 * Pure rules for roadmap T43: the Spawner Dungeon's "spawner as phase" fight in the Brood
 * Chamber (boss workshop 2026-10-11, decision 6b). {@link CapstoneFights} and
 * {@link BroodPhases} read these numbers; nothing here touches the world.
 *
 * <h2>The cage order</h2>
 * The four caged spawners of {@link CapstoneSpecs#BROOD_SPAWNERS} are sorted the way
 * {@link CapstoneFights#spawnerPositions} sorts them ((x, z), then y). The cage's place in that
 * order fixes which earlier Act 1 mechanic it frees when it is done (broken, or doused by the
 * exhaust rule):
 * <ol>
 *   <li>{@link Mechanic#COFFINS}: the Ossuary's coffins crack and stand,</li>
 *   <li>{@link Mechanic#BRICKS}: the Infestation's bricks creep and burst,</li>
 *   <li>{@link Mechanic#DARK}: the Mineshaft's lights fail and cave spiders come,</li>
 *   <li>{@link Mechanic#DRIP}: the Lush Caves' potion drip falls.</li>
 * </ol>
 * So the party chooses its own order by which cage it breaks first; the mapping itself is fixed.
 *
 * <h2>The mother</h2>
 * When all four cages are done the brood mother arrives: a scaled cave spider with a boss bar,
 * the retinue of {@link BroodWave} at her side, the Kennels handler beat on her half health and a
 * drip at double speed. The pad holds until she and every tagged brood mob are dead, which the
 * existing {@link BroodWave.Phase} math already expresses.
 */
final class BroodPhaseRules {

    private BroodPhaseRules() {}

    /** One earlier mechanic per cage, in cage order. */
    enum Mechanic {
        /** The Ossuary's coffins crack and stand ({@code OssuaryFinale}, via {@code MineGuardianCoffins}). */
        COFFINS,
        /** The Infestation's bricks creep and burst ({@code CreepingBricks}). */
        BRICKS,
        /** The Mineshaft's lights fail and cave spiders come ({@code MineshaftFinale}). */
        DARK,
        /** The Lush Caves' potion drip falls ({@code PotionDrip}). */
        DRIP
    }

    /** The mechanic each cage frees, in {@link CapstoneFights#spawnerPositions} order. */
    static final Mechanic[] ORDER = {Mechanic.COFFINS, Mechanic.BRICKS, Mechanic.DARK, Mechanic.DRIP};

    /** Which mechanic the {@code index}'th cage frees; indices past the order free nothing. */
    static Mechanic mechanicFor(int index) {
        return index >= 0 && index < ORDER.length ? ORDER[index] : null;
    }

    // ---- per mechanic headcounts ------------------------------------------------

    /** The most coffins the Ossuary phase raises, whatever the party. */
    static final int MAX_COFFINS = 4;
    /** The most creeping brick seeds the Infestation phase plants, whatever the party. */
    static final int MAX_BRICK_SEEDS = 6;
    /** The most cave spiders the dark phase adds, whatever the party. */
    static final int MAX_DARK_SPIDERS = 6;

    /** The coffins the Ossuary phase raises: two for a party of one, plus one per extra member. */
    static int coffinsFor(int party) {
        return Math.min(MAX_COFFINS, 1 + Math.max(1, party));
    }

    /** The creeping brick seeds the Infestation phase plants: four for a solo, plus one per extra member. */
    static int brickSeedsFor(int party) {
        return Math.min(MAX_BRICK_SEEDS, 3 + Math.max(1, party));
    }

    /** The cave spiders the dark phase adds: three for a solo, plus one per extra member. */
    static int darkSpidersFor(int party) {
        return Math.min(MAX_DARK_SPIDERS, 2 + Math.max(1, party));
    }

    /** How much room the mob cap leaves for new arrivals; never negative. */
    static int room(int cap, int alive) {
        return Math.max(0, Math.max(1, cap) - Math.max(0, alive));
    }

    // ---- the drip ----------------------------------------------------------------

    /**
     * Ticks between chamber drips: the Lush interval normally, halved once the mother is up, never
     * under two seconds so a brew always lands after its fall tell.
     */
    static int dripTicks(int baseTicks, boolean fast) {
        int base = Math.max(1, baseTicks);
        return Math.max(40, fast ? base / 2 : base);
    }

    // ---- the brood mother ---------------------------------------------------------

    /** The mother's display name and boss bar title. */
    static final String MOTHER_NAME = "The Brood Mother";

    /** How much bigger than a cave spider the mother stands. */
    static final double MOTHER_SCALE = 2.6;

    /** The mother's max health: 80 for a party of one, plus 30 per extra member. */
    static double motherHealth(int party) {
        return 80.0 + 30.0 * (Math.max(1, party) - 1);
    }

    /** The mother's attack damage: 6 for a party of one, plus 1.5 per extra member. */
    static double motherDamage(int party) {
        return 6.0 + 1.5 * (Math.max(1, party) - 1);
    }

    /** The Kennels windup the mother holds at half health, in ticks: five seconds, never under two. */
    static long windupTicks() {
        return Math.max(40, KennelsFinaleRules.WINDUP_TICKS);
    }

    // ---- the mother's web -----------------------------------------------------------------------------

    /** Ticks after she arrives before her first spit, and between spits after that. */
    static final int WEB_FIRST_TICKS = 100;
    static final int WEB_INTERVAL_TICKS = 200;

    /** The tiles one web covers (a circle of this radius) and the marked warning before it lands. */
    static final double WEB_RADIUS = 2.0;
    static final int WEB_WARN_TICKS = 30;

    /** How long a landed web holds its ground and how often it applies its bite. */
    static final int WEB_LIVE_TICKS = 160;
    static final int WEB_TICK_TICKS = 20;

    /** The slowness and damage one tick in a web applies; stepping out ends both. */
    static final int WEB_SLOW_TICKS = 60;
    static final float WEB_DAMAGE = 2.0f;
}
