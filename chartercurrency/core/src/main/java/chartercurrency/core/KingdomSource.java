package chartercurrency.core;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Every question this mod asks about kingdoms, behind one interface so only one class
 * knows where the answers come from.
 *
 * <p>In production the answers come from Charter ({@code CharterKingdomSource});
 * without Charter they come from {@link ManualKingdomSource}. The mod never keeps its
 * own copy of membership: it asks every time it needs to know.
 *
 * <p>"Kingdom" here is the top level organization. Treasuries are keyed by an
 * organization id and may belong to any organization; Renown and the Mint tax follow
 * {@link #kingdomOf}.
 */
public interface KingdomSource {

    /** The kingdom id the player belongs to, empty if none. */
    Optional<String> kingdomOf(UUID player);

    /** The Lord of an organization, empty if the office is vacant or the id is unknown. With two Lords, the first seated. */
    Optional<UUID> lordOf(String kingdomId);

    /** Every Lord of an organization: none, one, or two when a kingdom has two Lords. */
    default java.util.List<UUID> lordsOf(String kingdomId) {
        return lordOf(kingdomId).map(java.util.List::of).orElse(java.util.List.of());
    }

    /** A name for chat; falls back to the id for an unknown organization. */
    String displayName(String kingdomId);

    /** Every organization id this source knows. */
    Collection<String> kingdomIds();

    /** Whether this source knows who holds land, so {@link #landOwner} means something. */
    default boolean knowsLand() {
        return false;
    }

    /** The kingdom id holding a chunk, empty when nobody does or land is unknown. */
    default Optional<String> landOwner(String dimension, int chunkX, int chunkZ) {
        return Optional.empty();
    }
}
