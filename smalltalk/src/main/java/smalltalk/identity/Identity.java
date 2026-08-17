package smalltalk.identity;

/**
 * A villager's derived identity. Nothing here is ever written to disk --
 * {@link IdentityDeriver#derive} recomputes it on demand from the villager's
 * UUID, which is itself stable forever (SPEC.md section 2).
 */
public record Identity(String derivedName, Personality personality,
                        ItemCategory likedCategory, ItemCategory dislikedCategory) {
}
