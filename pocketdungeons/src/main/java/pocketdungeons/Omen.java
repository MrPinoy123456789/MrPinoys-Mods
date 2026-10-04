package pocketdungeons;

/**
 * M48: the run-level pressure system's pure math (spec 5.2, 5.3, 5.4).
 *
 * <p>Omen is an integer 0 to 4 per floor, accumulated from dwell time, sensor
 * pulses, shrieks, the Ominous Bargain, and Barred Vault clears. The per-floor
 * value is clamped; the <em>sum</em> across floors between two safe room visits
 * keys the finish table, which determines the keystone consequence (level change
 * and chest count) on completion.
 *
 * <p>No state lives here. The caller tracks the current floor's omen and hands
 * it to {@link #clamp} or {@link #add} on every source event; the sum and the
 * finish table are computed at completion from the per-floor values. This is
 * the same discipline as {@link KeystoneMath} and {@link DifficultyProfile}: the
 * arithmetic is easy to get wrong at the boundaries and trivial to test with
 * plain {@code javac}.
 */
final class Omen {

    private Omen() {}

    /** Per-floor omen is clamped to this range. */
    static final int MIN_OMEN = 0;
    static final int MAX_OMEN = 4;

    /**
     * What raised the omen, for the cue a player sees and hears when it rises.
     * {@code DEPTH} is a zone's head start on a floor past its usual length
     * ({@link ZoneRules#baseOmen}).
     */
    enum Source { DWELL, SENSOR, SHRIEK, BARGAIN, DEPTH, SILENCE }

    // ---- source table (spec 5.2) ----------------------------------------

    /**
     * Dwell: +1 per 90 seconds in an unsolved cell beyond the first 60.
     *
     * <p>Spec 5.4: dwelling only counts while the player is in a cell whose
     * situation is unsolved. A cleared cell and the staging room are free.
     * This overload applies the exemption: pass {@code unsolved = false} for a
     * cleared cell or {@code stagingRoom = true} for the staging room and the
     * contribution is 0 regardless of time spent.
     *
     * @param seconds total time spent in this cell
     * @param unsolved whether this cell's situation is still unsolved
     * @param stagingRoom whether this is the staging room
     */
    static int dwellContribution(int seconds, boolean unsolved, boolean stagingRoom) {
        if (!unsolved || stagingRoom) {
            return 0;
        }
        return dwellContribution(seconds);
    }

    /**
     * The raw dwell formula with no exemption check: +1 per 90 seconds
     * beyond the first 60. Exposed for callers that have already filtered
     * to unsolved, non-staging cells.
     */
    static int dwellContribution(int seconds) {
        if (seconds <= 60) {
            return 0;
        }
        return (seconds - 60) / 90;
    }

    /**
     * Sensor pulses: +1 per 5 pulses (spec 5.2, Pot Room or Landing).
     */
    static int sensorContribution(int pulses) {
        return Math.max(0, pulses) / 5;
    }

    /**
     * Shrieks: +1 each (spec 5.2).
     */
    static int shriekContribution(int shrieks) {
        return Math.max(0, shrieks);
    }

    /**
     * Drinking the Ominous Bargain sets the floor's omen to 4 (spec 5.2).
     * Use {@link #set} rather than {@link #add}; the bargain replaces, it
     * does not stack.
     */
    static int bargainOmen() {
        return MAX_OMEN;
    }

    /**
     * Clearing a Barred Vault: -1 (spec 5.2).
     */
    static int barredVaultContribution() {
        return -1;
    }

    // ---- clamp and accumulate -------------------------------------------

    /**
     * Clamps a per-floor omen value to [{@value MIN_OMEN}, {@value MAX_OMEN}].
     */
    static int clamp(int omen) {
        return Math.max(MIN_OMEN, Math.min(MAX_OMEN, omen));
    }

    /**
     * Adds a contribution to the current per-floor omen and clamps.
     */
    static int add(int current, int contribution) {
        return clamp(current + contribution);
    }

    /**
     * Sets the per-floor omen to a specific value (for the Ominous Bargain),
     * clamped.
     */
    static int set(int value) {
        return clamp(value);
    }

    // ---- ominous roll (2026-10-01) ----------------------------------------

    /**
     * The chance the next floor turns ominous, rolled once the party has
     * chosen a door. It is the share of the possible omen the party has banked
     * so far this interval: every cleared floor could have carried
     * {@value MAX_OMEN}, so a calm interval never rolls it and a dire one
     * nearly always does. The first floor of an interval has nothing banked
     * and so is never ominous by roll.
     *
     * @param bankedOmenSum the cleared floors' clamped omen, summed
     * @param floorsBanked  how many floors that sum covers
     */
    static double ominousChance(int bankedOmenSum, int floorsBanked) {
        if (floorsBanked <= 0 || bankedOmenSum <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) bankedOmenSum / (floorsBanked * MAX_OMEN));
    }

    // ---- per-floor sum (spec 5.4) ---------------------------------------

    /**
     * The sum of per-floor omens across floors between two safe room visits.
     * Each floor's omen is clamped before summing, so a caller that forgot to
     * clamp does not corrupt the total.
     */
    static int floorSum(int[] perFloorOmens) {
        int sum = 0;
        for (int omen : perFloorOmens) {
            sum += clamp(omen);
        }
        return sum;
    }

    // ---- finish table (spec 5.2, 5.4) -----------------------------------

    /**
     * The finish table band for a given omen sum and floor count.
     *
     * <p>Thresholds scale to {@code floorCount}, the floors the sum was
     * gathered over (the interval's cleared floors, plus the one in progress
     * on the bar): 0 to {@code floorCount} is the low band,
     * {@code floorCount + 1} to {@code 3 * floorCount} is the mid band, and
     * {@code 3 * floorCount + 1} to {@code 4 * floorCount} is the high band.
     * For 1 floor: 0 to 1, 2 to 3, 4. For 3 floors: 0 to 3, 4 to 9, 10 to
     * 12. For 5 floors: 0 to 5, 6 to 15, 16 to 20. Scaling to the floors
     * actually cleared is what lets a party bank after any floor: one calm
     * floor is as calm as three.
     *
     * @return 0 for low, 1 for mid, 2 for high
     */
    static int band(int sum, int floorCount) {
        int floors = Math.max(1, floorCount);
        int lowMax = floors;
        int midMax = 3 * floors;
        if (sum <= lowMax) {
            return 0;
        }
        if (sum <= midMax) {
            return 1;
        }
        return 2;
    }

    /**
     * The largest omen sum that still lands in {@code band}: the band's upper
     * edge from {@link #band}. The high band's edge is the most omen
     * {@code floorCount} floors can gather.
     */
    static int bandCeiling(int band, int floorCount) {
        int floors = Math.max(1, floorCount);
        return switch (band) {
            case 0 -> floors;
            case 1 -> 3 * floors;
            default -> MAX_OMEN * floors;
        };
    }

    /**
     * How far {@code sum} has climbed toward the next band, 0 to 1: the sum
     * against the first value of the next band up, or against the most an
     * interval can gather once it is already in the high band.
     */
    static float bandProgress(int sum, int floorCount) {
        int band = band(sum, floorCount);
        int next = band < 2 ? bandCeiling(band, floorCount) + 1
                : bandCeiling(band, floorCount);
        return Math.max(0.0f, Math.min(1.0f, (float) Math.max(0, sum) / next));
    }

    /**
     * Whether the band lets the interval's door steps bank (spec 5.2): 1 for
     * the low and mid bands, 0 for the high band. How many levels that is,
     * {@link IntervalBanking} works out from the floors' door steps.
     */
    static int levelChange(int band) {
        return band < 2 ? 1 : 0;
    }

    /**
     * Reward chest count from the finish table band (spec 5.2). Kept for old
     * callers; rewards no longer depend on omen, so use
     * {@link #baseRewardChests()} for the new chest floor.
     */
    static int chestCount(int band) {
        return switch (band) {
            case 0 -> 3;
            case 1 -> 2;
            default -> 1;
        };
    }

    /**
     * Every completed floor pays the base number of reward chests, plus the
     * depth bonus. Omen now adds danger, not reward cuts.
     */
    static int baseRewardChests() {
        return 3;
    }
}
