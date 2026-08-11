package chatdonkey.core;

import java.util.List;

/**
 * What a demand event wants, as item ids (SPEC.md section 4).
 *
 * <p>Ids rather than items, so {@code core} stays Minecraft-free and the whole
 * thing is operator-editable — the same trick {@code bounties} uses for mob ids.
 *
 * <p>The shape is deliberately the Food Critic's, generalised: an ordinary thing
 * it will accept, and a fancier version of that thing it would much rather have.
 * Carrot and golden carrot were the first pair, not the only possible one.
 */
public record Demand(String ordinaryItem, String premiumItem,
                     List<String> duplicates, int multiplier) {

    /** An event with no demand -- it cannot be satisfied, only waited out or bribed. */
    public static final Demand NONE = new Demand(null, null, List.of(), 0);

    /** The Food Critic shape: one item it wants, one fancier version. */
    public Demand(String ordinaryItem, String premiumItem) {
        this(ordinaryItem, premiumItem, List.of(), 0);
    }

    public Demand {
        duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
    }

    public boolean exists() {
        return (ordinaryItem != null && !ordinaryItem.isBlank()) || duplicatesAnything();
    }

    /**
     * Whether this event hands back more of whatever it was given.
     *
     * <p>A separate shape from the ordinary/premium pair: the reward is not a
     * gift tier but a multiple of the item itself, so the drop table does not
     * come into it.
     */
    public boolean duplicatesAnything() {
        return !duplicates.isEmpty() && multiplier > 1;
    }

    /** Whether this specific item is one the donkey will copy. */
    public boolean duplicates(String itemId) {
        return itemId != null && duplicatesAnything() && duplicates.contains(itemId);
    }

    /** Whether a premium upgrade is configured; without one, only the base tier is reachable. */
    public boolean hasPremium() {
        return premiumItem != null && !premiumItem.isBlank();
    }

    /**
     * Which rung a given item id satisfies, or {@code null} if it is not wanted.
     *
     * <p>Premium is checked first so an event that lists the same id for both
     * resolves to the better tier rather than the worse one.
     */
    public Offering offeringFor(String itemId) {
        if (itemId == null) {
            return null;
        }
        if (hasPremium() && itemId.equals(premiumItem)) {
            return Offering.PREMIUM;
        }
        if (itemId.equals(ordinaryItem)) {
            return Offering.ORDINARY;
        }
        if (duplicates(itemId)) {
            return Offering.DUPLICATED;
        }
        return null;
    }
}
