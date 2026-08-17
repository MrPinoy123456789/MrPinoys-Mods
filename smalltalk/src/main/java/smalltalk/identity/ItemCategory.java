package smalltalk.identity;

/**
 * The eight buckets a resident's liked/disliked quirk can land in. Which
 * concrete items belong to which category is data, not code -- see
 * {@code social.GiftCategories} -- so this enum only names the buckets.
 * Frozen ordering, same rule as {@link Personality}.
 */
public enum ItemCategory {
    FOOD,
    FLOWERS,
    ORES_GEMS,
    BOOKS_PAPER,
    TOOLS,
    REDSTONE,
    DECORATION,
    MUSIC
}
