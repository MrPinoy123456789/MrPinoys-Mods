package chatdonkey.core;

/**
 * The drop table tiers from SPEC.md section 5. The gift is a punchline, not a
 * reward -- it must feel underwhelming against the hassle.
 *
 * <p>M1 ships {@link #STANDARD} only. The others exist so {@link GiftTable}'s
 * selection logic is written and tested once.
 */
public enum GiftTier {

    /** Player hit the donkey 3+ times: nothing but a worse punchline -- M2. */
    GRUDGE,
    /** Waited it out: 2-8 cobblestone. */
    STANDARD,
    /** Bribed with a diamond -- M2. */
    GRACIOUS,
    /** Met the event's demand -- M2. */
    SATISFIED,
    /** Rare roll, or a golden carrot to the Food Critic -- M3. */
    GOLDEN
}
