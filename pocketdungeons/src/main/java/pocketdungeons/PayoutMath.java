package pocketdungeons;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Streak transitions and payout arithmetic, with no Minecraft imports -- same
 * discipline as {@link DifficultyProfile} and {@link DoorMask}, and for the same
 * reason: this is the part of U5 that is easy to get subtly wrong and trivial to
 * test with plain {@code javac}.
 *
 * <p>The streak rule here is deliberately simpler than
 * {@code kamutotems.core.Streak}: no grace days. Kamutotems grants grace because
 * missing a day there costs a long accumulation; here a streak only scales a
 * payout, so a reset is mild and the code is a third of the size. The date-key
 * format is shared with it on purpose ({@link LocalDate#toString()}), so a player
 * who plays both mods sees both streaks turn over at the same moment.
 */
final class PayoutMath {

    private PayoutMath() {}

    /**
     * The streak a completion on {@code todayKey} earns.
     *
     * <p>Same day leaves it alone, the next day increments, anything else starts
     * over at 1. "Anything else" includes a clock that has gone backwards and a
     * date key that will not parse -- a corrupt or hand-edited entry resets the
     * streak rather than throwing, because the alternative is a save file that
     * cannot be loaded over a cosmetic number.
     */
    static int nextStreak(String lastCompletedDateKey, String todayKey, int currentStreak) {
        if (todayKey == null || todayKey.isBlank()) {
            return Math.max(1, currentStreak);
        }
        if (lastCompletedDateKey == null || lastCompletedDateKey.isBlank()) {
            return 1;
        }
        if (todayKey.equals(lastCompletedDateKey)) {
            // A second run on the same day still counts as a run; it just does not
            // advance the streak. Floor at 1: getting here at all means a
            // completion has happened.
            return Math.max(1, currentStreak);
        }
        long gap;
        try {
            gap = ChronoUnit.DAYS.between(
                    LocalDate.parse(lastCompletedDateKey), LocalDate.parse(todayKey));
        } catch (Exception e) {
            return 1;
        }
        return gap == 1 ? Math.max(1, currentStreak) + 1 : 1;
    }

    /**
     * How many payout items one completion is worth.
     *
     * <p>{@code (base + perTier * (tier - 1))} scaled by the streak bonus, which
     * starts at zero on day one and is capped. Integer arithmetic throughout, with
     * the multiply before the divide so a 10% bonus on a 6-item base is not
     * rounded away to nothing.
     */
    static int count(int baseCount, int perTier, int lootTier,
                     int streak, int bonusPercent, int capPercent) {
        int tier = Math.max(1, lootTier);
        long flat = (long) baseCount + (long) perTier * (tier - 1);
        int days = Math.max(0, streak - 1);
        long bonus = Math.min((long) capPercent, (long) bonusPercent * days);
        long scaled = flat * (100L + bonus) / 100L;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, scaled));
    }

    /**
     * The same count, scaled by the two multipliers U6 and U7 add on top.
     *
     * <p>Order is fixed and worth stating: the streak bonus first (it is the
     * daily-return lever), then the keystone level, then the ominous run. Each is
     * applied to the running total rather than summed into one percentage, so a
     * long streak on a high key on an ominous run compounds -- which is the point,
     * since all three cost something a machine cannot pay.
     */
    static int count(int baseCount, int perTier, int lootTier,
                     int streak, int bonusPercent, int capPercent,
                     int keystoneLevel, int perLevelPercent,
                     boolean ominousRun, int ominousPercent) {
        long value = count(baseCount, perTier, lootTier, streak, bonusPercent, capPercent);
        value = value * (100L + (long) Math.max(0, perLevelPercent)
                * Math.max(0, keystoneLevel)) / 100L;
        if (ominousRun) {
            value = value * Math.max(100L, ominousPercent) / 100L;
        }
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    /** The multiplier {@link #count(int, int, int, int, int, int)} is applying, as a percentage. */
    static int streakBonusPercent(int streak, int bonusPercent, int capPercent) {
        return (int) Math.min((long) capPercent, (long) bonusPercent * Math.max(0, streak - 1));
    }
}
