package pocketdungeons;

import java.util.List;

/**
 * What an interval banks when it ends, per member: the omen band, the keystone
 * levels and the progress carried to the next interval, and the chest count.
 * No Minecraft imports, the same discipline as {@link KeystoneMath} and
 * {@link Omen}: the rounding and the band edges are the part that is easy to
 * get wrong and trivial to test with plain {@code javac}.
 *
 * <p>The rule, from the owner's decision on the 2026-09 audit (B4): every
 * cleared floor banks its own door step, and the interval's level gain is the
 * average step over the interval length. With {@code floorsPerSafeVisit} of 3,
 * three Greater (+3) floors bank +3 levels, the same pace the forced safe room
 * used to give; one +3 floor banks +1; two free (+1) floors bank nothing yet
 * and carry 2 of the 3 steps a level costs into the next interval. So a floor
 * is worth {@code step / floorsPerSafeVisit} levels wherever the party banks,
 * and banking early or late never changes the ladder's pace, only the risk.
 *
 * <p>A high (band 2) finish banks no levels, as it always has, and leaves the
 * carried progress exactly where it was: the floors of a dire interval neither
 * add to it nor spend it. Leaving at a checkpoint instead of going home
 * settles one band worse ({@link #LEAVE_PENALTY}).
 */
final class IntervalBanking {

    private IntervalBanking() {}

    /** Bands a checkpoint exit costs: the interval banks as if its omen were one band higher. */
    static final int LEAVE_PENALTY = 1;

    /** The highest band, the one that banks no levels. */
    static final int WORST_BAND = 2;

    /**
     * One member's settlement.
     *
     * @param band     the omen band after any penalty, 0 to 2
     * @param levels   keystone levels gained
     * @param progress door steps carried into the next interval, below
     *                 {@code floorsPerSafeVisit} whenever levels were banked
     * @param chests   the chest count the band and the depth bonus pay
     */
    record Settlement(int band, int levels, int progress, int chests) {}

    /**
     * The band an interval of {@code floorsCleared} floors settles in:
     * {@link Omen#band} with thresholds scaled to the floors actually cleared,
     * made {@code penalty} bands worse and capped at the worst.
     */
    static int band(int omenSum, int floorsCleared, int penalty) {
        return Math.min(WORST_BAND, Omen.band(omenSum, Math.max(1, floorsCleared)) + Math.max(0, penalty));
    }

    /** The door steps a list of cleared floors adds up to. */
    static int stepSum(List<Integer> floorSteps) {
        int sum = 0;
        for (int step : floorSteps) {
            sum += Math.max(0, step);
        }
        return sum;
    }

    /**
     * Settles an interval for one member.
     *
     * @param floorSteps         each cleared floor's door step, in order
     * @param omenSum            the cleared floors' clamped omen, summed
     * @param carried            the member's progress carried from earlier intervals
     * @param floorsPerSafeVisit the interval length: how many steps a level costs
     * @param penalty            0 for going home, {@link #LEAVE_PENALTY} for leaving
     * @param bonusChests        the zone's depth bonus for this many floors
     */
    static Settlement settle(List<Integer> floorSteps, int omenSum, int carried,
                             int floorsPerSafeVisit, int penalty, int bonusChests) {
        int perLevel = Math.max(1, floorsPerSafeVisit);
        // Not reduced modulo perLevel: an operator who shortens the interval
        // must not shave steps a player already earned. The next banking
        // floor turns any surplus into levels.
        int kept = Math.max(0, carried);
        int band = band(omenSum, floorSteps.size(), penalty);
        int chests = Omen.chestCount(band) + Math.max(0, bonusChests);
        if (floorSteps.isEmpty() || Omen.levelChange(band) == 0) {
            return new Settlement(band, 0, kept, chests);
        }
        int total = kept + stepSum(floorSteps);
        return new Settlement(band, total / perLevel, total % perLevel, chests);
    }

    // ---- words (pure strings; callers wrap them in components) ---------------

    /** {@code "+2 levels"}, {@code "+1 level"}, {@code "+0 levels"}. */
    static String levels(int levels) {
        return "+" + levels + (levels == 1 ? " level" : " levels");
    }

    /** {@code "3 chests"} or {@code "1 chest"}. */
    static String chests(int chests) {
        return chests + (chests == 1 ? " chest" : " chests");
    }

    /**
     * The door screen's line for what a door adds: {@code "+3 toward your
     * key, +4 so far"}. The "so far" is this interval's cleared floors, before
     * this one.
     */
    static String doorLine(int step, int stepsSoFar) {
        return "+" + step + " toward your key, +" + stepsSoFar + " so far";
    }

    /**
     * The go-home screen: what pulling the lever would bank right now, for the
     * owner. {@code goodTime} is true once the interval has run its usual
     * length, the moment the screen and its bulb light up.
     */
    static String homeScreen(Settlement now, int floorsPerSafeVisit, boolean goodTime) {
        String title = goodTime ? "TIME TO GO HOME" : "HOME";
        String bank = now.levels() == 0 && Omen.levelChange(now.band()) == 0
                ? "Banks no levels"
                : "Banks " + levels(now.levels()) + ", " + now.progress() + "/" + Math.max(1, floorsPerSafeVisit)
                        + " kept";
        return title + "\n" + bank + "\n" + chests(now.chests()) + ", " + OmenBarText.bandName(now.band());
    }

    /** The line each member reads when their interval banks. */
    static String bankedLine(Settlement settled, int floorsPerSafeVisit, boolean leftEarly) {
        String opening = leftEarly
                ? "You leave at the checkpoint, and the interval banks one band worse: "
                : "The interval banks: ";
        return opening + levels(settled.levels()) + ", " + settled.progress() + " of "
                + Math.max(1, floorsPerSafeVisit) + " toward the next, " + chests(settled.chests())
                + " (" + OmenBarText.bandName(settled.band()) + ").";
    }
}
