package smalltalk.social;

/**
 * SPEC.md section 6.2's reaction table. Three tiers, not the earlier
 * four-tier draft's "loved/liked/neutral/disliked": whether an item lands
 * in a resident's derived liked/disliked category is binary, so there's no
 * signal left to split "liked" from "neutral" -- everything that isn't
 * their specific liked or disliked category gets the same modest, friendly
 * reaction.
 */
public enum GiftReaction {
    LOVED(8),
    NEUTRAL(1),
    DISLIKED(-2);

    private final int familiarityDelta;

    GiftReaction(int familiarityDelta) {
        this.familiarityDelta = familiarityDelta;
    }

    public int familiarityDelta() {
        return familiarityDelta;
    }
}
