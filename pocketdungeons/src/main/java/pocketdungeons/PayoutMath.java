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
     * How many of the reward room's three chests are earned, scored against how
     * much of the clock is left when the run is completed.
     *
     * <p>Over time (no seconds remaining) earns none -- the run still counts as
     * finished, but the chests stay empty. {@code totalSeconds} is floored at 1 so
     * a misconfigured zero-length timer cannot divide by zero; the boundaries are
     * inclusive of the threshold percentage, so finishing at exactly 60% or 80%
     * used still earns the better tier.
     */
    static int chestCount(int secondsRemaining, int totalSeconds,
                          int threePercent, int twoPercent) {
        if (secondsRemaining <= 0) {
            return 0;
        }
        int usedPercent = 100 - (secondsRemaining * 100 / Math.max(1, totalSeconds));
        if (usedPercent <= threePercent) {
            return 3;
        }
        if (usedPercent <= twoPercent) {
            return 2;
        }
        return 1;
    }
}
