package pocketdungeons;

/**
 * Reward arithmetic with no Minecraft imports -- same discipline as
 * {@link DifficultyProfile} and {@link DoorMask}, and for the same reason: this
 * is the part of a reward curve that is easy to get subtly wrong at the
 * boundaries and trivial to test with plain {@code javac}.
 *
 * <p>U8 deletes the streak entirely -- the keystone level is the ladder now --
 * and replaces the item payout with a chest count scored against the run's own
 * clock. This file is kept as the home for that arithmetic rather than moved,
 * since it is the one piece of U5's original reward math that survives.
 */
final class PayoutMath {

    private PayoutMath() {}

    /**
     * How many of the reward room's three chest slots are earned (and filled
     * with loot; the rest stay air, per {@code TrialContent.placeCompletionChests}),
     * scored against how much of the clock is left when the run is completed.
     *
     * <p>Over time (no seconds remaining) earns none -- the run still counts as
     * finished, but the chests stay empty. {@code totalSeconds} is floored at 1 so
     * a misconfigured zero-length timer cannot divide by zero; the boundaries are
     * inclusive of the threshold percentage, so finishing at exactly 60% or 80%
     * used still earns the better tier.
     *
     * <p>Capped at 2, not 3: three loot-table rolls landing beside each other
     * in the reward room compounded into more items than the per-table roll
     * count (PD-54, PD-59) was ever meant to allow on its own, even after that
     * fix cut each individual chest down to one or two items. The fast and
     * medium finish tiers collapse to the same reward here (both 2) rather
     * than losing the distinction between medium and slow, which a straight
     * "subtract one from every tier" would have done. The physical third
     * chest slot stays in the template; it just never gets filled.
     *
     * <p>{@code threePercent} is kept in the signature, superseded rather
     * than deleted (same codec migration discipline `CONVENTIONS.md`
     * describes for a superseded field): the config value it reads from,
     * {@code threeChestPercent}, is a server operator's tuned setting, not
     * something to invalidate out from under an existing
     * {@code pocketdungeons.json} on the first pass this cap shipped in. If
     * the cap ever needs to distinguish a fast finish from a merely timely
     * one again, the threshold is already here to reach for.
     */
    static int chestCount(int secondsRemaining, int totalSeconds,
                          int threePercent, int twoPercent) {
        if (secondsRemaining <= 0) {
            return 0;
        }
        // PD-42: widened to long before multiplying. secondsRemaining * 100
        // overflows int past roughly 21.4 million seconds, and
        // KeystoneMath.timerSeconds deliberately allows a timer that large
        // (clamped only at Integer.MAX_VALUE), so this is the one place that
        // guard fed a value the int math below could not hold.
        int usedPercent = 100 - (int) ((long) secondsRemaining * 100 / Math.max(1, totalSeconds));
        if (usedPercent <= twoPercent) {
            return 2;
        }
        return 1;
    }
}
