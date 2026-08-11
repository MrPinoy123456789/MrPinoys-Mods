package chatdonkey.core;

/**
 * What the player handed over to satisfy a demand event (SPEC.md section 4).
 *
 * <p>Two rungs, not a list of items: the donkey wants a thing, and it is
 * *delighted* by the fancy version of that thing. Which actual items those are
 * is per-event config, so {@code core} never names a Minecraft item.
 *
 * <p>This replaces the old {@code Treat} enum, which hardcoded carrots. The
 * carrot was only ever one instance of the pattern.
 */
public enum Offering {

    /** The thing it asked for. Meets the demand. */
    ORDINARY,
    /** The fancy version. Above and beyond, and the donkey knows it. */
    PREMIUM,
    /**
     * A raw resource handed to a duplicating donkey.
     *
     * <p>Pays in more of that same item rather than from the drop table, so
     * {@link #tier()} does not apply -- the caller must check {@link
     * #isDuplication()} before reaching for a gift tier.
     */
    DUPLICATED;

    /** Whether the reward is a multiple of the item given, not a drop-table tier. */
    public boolean isDuplication() {
        return this == DUPLICATED;
    }

    /** The gift tier this earns (SPEC.md section 5). Meaningless for {@link #DUPLICATED}. */
    public GiftTier tier() {
        return this == PREMIUM ? GiftTier.GOLDEN : GiftTier.SATISFIED;
    }
}
