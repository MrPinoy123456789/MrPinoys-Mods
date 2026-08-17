package smalltalk.social;

/**
 * The four familiarity bands (SPEC.md section 7). Familiarity never touches
 * prices -- these gate dialogue depth and, later, which request types a
 * resident will offer.
 */
public enum FamiliarityTier {
    STRANGER(0),
    ACQUAINTANCE(20),
    FRIEND(50),
    DEAR_FRIEND(80);

    private final int floor;

    FamiliarityTier(int floor) {
        this.floor = floor;
    }

    /** The lowest score still inside this tier. */
    public int floor() {
        return floor;
    }

    public static FamiliarityTier of(int score) {
        if (score >= DEAR_FRIEND.floor) return DEAR_FRIEND;
        if (score >= FRIEND.floor) return FRIEND;
        if (score >= ACQUAINTANCE.floor) return ACQUAINTANCE;
        return STRANGER;
    }
}
