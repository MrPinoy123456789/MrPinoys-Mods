package kamutotems.core;

/**
 * Which half of an augment lands when it is used as an aura modifier.
 *
 * <p>Every non-delivery, non-default kamu has a non-{@code NONE} polarity,
 * so all seven carried kamu are legal in both the construct and aura systems.
 */
public enum Polarity {
    BOON,
    BANE,
    DUAL,
    NONE;

    /**
     * Whether this polarity can be bound to the given aura shape.
     *
     * <p>Every polarity is legal on every kind. Focus and Momentum release a
     * pulse exactly like Bloom's -- a bane reading lands outward, on
     * whatever is in range, not on the bearer -- so there is no "curse
     * yourself" case to refuse. {@code NONE} is the only exclusion: it is
     * not a legal aura modifier at all.
     */
    public boolean legalOn(AuraKind kind) {
        return kind != null && this != NONE;
    }
}
