package charter.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Answers kingdom questions for outside mods. Lookups are by organization id. */
public interface KingdomView {
    /** The top level organization (kingdom) the player belongs to, if any. */
    Optional<String> kingdomOf(UUID player);

    /** The Lord of an organization, if the office is filled. */
    Optional<UUID> lordOf(String orgId);

    /** Every Lord of an organization, first seated first: none, one, or two when a kingdom has two Lords. */
    default java.util.List<UUID> lordsOf(String orgId) {
        return lordOf(orgId).map(java.util.List::of).orElse(java.util.List.of());
    }

    /** May this player take {@code marks} of coin from the organization's treasury in one action. Default false. */
    default boolean maySpend(String orgId, UUID player, long marks) {
        return false;
    }

    String displayName(String orgId);

    Collection<String> orgIds();
}
