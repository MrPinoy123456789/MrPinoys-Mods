package chatdonkey.core;

/**
 * The drop-table numbers from SPEC.md section 5, which that section explicitly
 * wants to be config rather than constants.
 *
 * <p>Lives inside {@code settings.json} rather than a fourth file, because
 * SPEC.md section 9 names exactly three config files and {@code /donkey reload}
 * promises to re-read "all three".
 *
 * <p>The economics these control matter more than they look (DESIGN.md section
 * 5): an event paying a few cobblestone is negligible against the quizengine and
 * bounties faucets, while the bribe destroys a whole diamond. Raising the payout
 * numbers here turns a sink into a faucet, so they are worth thinking about
 * before turning up.
 */
public record GiftSettings(
        int standardMin, int standardMax,
        int graciousMin, int graciousMax,
        int satisfiedMin, int satisfiedMax,
        int goldenDiamonds,
        double goldenChance) {

    public static GiftSettings defaults() {
        return new GiftSettings(2, 8, 4, 8, 8, 16, 1, 0.02);
    }

    public GiftSettings sanitised() {
        return new GiftSettings(
                Math.max(0, standardMin), Math.max(Math.max(0, standardMin), standardMax),
                Math.max(0, graciousMin), Math.max(Math.max(0, graciousMin), graciousMax),
                Math.max(0, satisfiedMin), Math.max(Math.max(0, satisfiedMin), satisfiedMax),
                Math.max(0, goldenDiamonds),
                Math.min(1.0, Math.max(0.0, goldenChance)));
    }

    int minFor(GiftTier tier) {
        return switch (tier) {
            case GRUDGE -> 0;
            case STANDARD -> standardMin;
            case GRACIOUS -> graciousMin;
            case SATISFIED -> satisfiedMin;
            case GOLDEN -> goldenDiamonds;
        };
    }

    int maxFor(GiftTier tier) {
        return switch (tier) {
            case GRUDGE -> 0;
            case STANDARD -> standardMax;
            case GRACIOUS -> graciousMax;
            case SATISFIED -> satisfiedMax;
            case GOLDEN -> goldenDiamonds;
        };
    }
}
