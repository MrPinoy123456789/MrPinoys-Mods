package pocketdungeons;

/**
 * J3 (plan 2026-10-06-2): the run's risk meter, as pure math. Omen is the
 * count of deaths this trip, 0 to {@link #MAX_OMEN}; the player sees it as
 * lives, {@code LIVES - omen}. A trip starts at five lives, each death takes
 * one, and the death that would take the omen past four ends the run: five
 * deaths total. Danger scales with omen ({@code omenDangerScalePerOmen});
 * nothing else raises it. The other triggers (dwell, sculk pulses, shrieks,
 * the Ominous Bargain, Silence consumables) spawn waves or raise the floor's
 * level instead; see {@code PressureSources}.
 *
 * <p>No state lives here. The caller holds the trip's omen on
 * {@code IntervalState.omen} and hands it to {@link #clamp} or {@link #add};
 * the same discipline as {@link KeystoneMath} and {@link DifficultyProfile}:
 * the arithmetic is easy to get wrong at the boundaries and trivial to test
 * with plain {@code javac}.
 */
final class Omen {

    private Omen() {}

    /** Omen is clamped to this range; the failing fifth death is checked before clamping. */
    static final int MIN_OMEN = 0;
    static final int MAX_OMEN = 4;

    /** A trip starts with this many lives: {@code LIVES - omen}. */
    static final int LIVES = MAX_OMEN + 1;

    /**
     * What raised the omen or spawned the wave, for the cue a player sees.
     * {@code DEATH} is the only source that raises omen; the rest name wave
     * triggers on the bar and in the journal.
     */
    enum Source { DEATH, DWELL, SENSOR, SHRIEK, BARGAIN, SILENCE, VAULT, DOOR }

    // ---- lives (J3) ------------------------------------------------------

    /** The most party members that add lives to a trip. */
    static final int MAX_LIFE_MEMBERS = 4;

    /**
     * The lives a trip starts with, fixed at its first door commit: {@value LIVES} solo, plus
     * {@code perExtraMember} for each further member up to {@value MAX_LIFE_MEMBERS} members
     * (solo 5, two 6, four 8 at the default of 1; a fifth member adds nothing).
     */
    static int maxLives(int members, int perExtraMember) {
        int counted = Math.max(1, Math.min(members, MAX_LIFE_MEMBERS));
        return LIVES + (counted - 1) * Math.max(0, perExtraMember);
    }

    /** The lives {@code omen} deaths leave of a trip that started with {@code max}: {@code max - omen}, never below 1. */
    static int lives(int omen, int max) {
        return Math.max(1, max) - clampTo(omen, max);
    }

    /**
     * Whether the next death ends the run: the omen is already at {@code max - 1}
     * (one life left), so the next death is the last.
     */
    static boolean nextDeathFails(int omen, int max) {
        return clampTo(omen, max) >= Math.max(1, max) - 1;
    }

    /**
     * The colour band of a lives meter: 0 green (more than three fifths of the trip's lives left),
     * 1 yellow (more than a fifth), 2 red. Solo five reads green at 5 and 4, yellow at 3 and 2, red at 1.
     */
    static int livesColourIndex(int lives, int max) {
        int m = Math.max(1, max);
        return lives * 5 > m * 3 ? 0 : lives * 5 > m ? 1 : 2;
    }

    /** Clamps the omen to what a trip of {@code max} lives can hold: 0 to {@code max - 1}. */
    static int clampTo(int omen, int max) {
        return Math.max(MIN_OMEN, Math.min(Math.max(1, max) - 1, omen));
    }

    /** {@link #add} for a trip of {@code max} lives: the omen can climb past {@value MAX_OMEN} when the party is bigger. */
    static int add(int current, int contribution, int max) {
        return clampTo(current + contribution, max);
    }

    // ---- wave trigger timing (spec 5.2, reworked J3) ---------------------

    /**
     * Dwell: one wave per 90 seconds in an unsolved cell beyond the first 60.
     *
     * <p>Spec 5.4: dwelling only counts while the player is in a cell whose
     * situation is unsolved. A cleared cell and the staging room are free.
     * Pass {@code unsolved = false} or {@code stagingRoom = true} and the
     * count is 0 regardless of time spent.
     *
     * @param seconds total time spent in this cell
     * @param unsolved whether this cell's situation is still unsolved
     * @param stagingRoom whether this is the staging room
     */
    static int dwellWaves(int seconds, boolean unsolved, boolean stagingRoom) {
        if (!unsolved || stagingRoom) {
            return 0;
        }
        return dwellWaves(seconds);
    }

    /** The raw dwell cadence with no exemption check: one wave per 90 seconds beyond the first 60. */
    static int dwellWaves(int seconds) {
        if (seconds <= 60) {
            return 0;
        }
        return (seconds - 60) / 90;
    }

    /** Sensor pulses per spawned wave outside the Ancient City. */
    static int pulsesPerWave(boolean ancientCity) {
        return ancientCity ? SculkOmen.ANCIENT_PULSES_PER_WAVE : SculkOmen.NORMAL_PULSES_PER_WAVE;
    }

    /** Whole waves owed by {@code pendingPulses} banked pulses. */
    static int wavesFromPulses(int pendingPulses, boolean ancientCity) {
        return Math.max(0, pendingPulses) / pulsesPerWave(ancientCity);
    }

    /** Pulses left over after {@code waves} were paid out of {@code pendingPulses}. */
    static int remainingPulses(int pendingPulses, int waves, boolean ancientCity) {
        return Math.max(0, pendingPulses - waves * pulsesPerWave(ancientCity));
    }

    // ---- clamp and accumulate -------------------------------------------

    /** Clamps the omen to [{@value MIN_OMEN}, {@value MAX_OMEN}]. */
    static int clamp(int omen) {
        return Math.max(MIN_OMEN, Math.min(MAX_OMEN, omen));
    }

    /** Adds a contribution to the trip's omen and clamps (a death, or relief). */
    static int add(int current, int contribution) {
        return clamp(current + contribution);
    }

    // ---- ominous roll (2026-10-01) ----------------------------------------

    /**
     * The chance the next floor turns ominous, rolled once the party has
     * chosen a door: the share of the trip's lives already lost. A clean trip
     * never rolls it; at four deaths every floor is ominous. The first floor
     * of a trip is always clean, so it is never ominous by roll.
     */
    static double ominousChance(int omen) {
        return (double) clamp(omen) / MAX_OMEN;
    }

    /**
     * Every completed floor pays the base number of reward chests, plus the
     * depth bonus. Omen adds danger, not reward cuts.
     */
    static int baseRewardChests() {
        return 3;
    }
}
