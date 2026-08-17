package smalltalk.identity;

/**
 * Six personalities, reweighting dialogue rather than gating content
 * (SPEC.md section 3). Ordering is part of the frozen identity hash --
 * see {@link IdentityDeriver}. Append-only past this point.
 */
public enum Personality {
    CHIPPER,
    GRUFF,
    DREAMY,
    BRISK,
    BASHFUL,
    SMUG
}
