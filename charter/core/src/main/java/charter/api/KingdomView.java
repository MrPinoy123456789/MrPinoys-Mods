package charter.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers kingdom questions for outside mods. All lookups are by organization id: the string form of
 * Charter's {@code OrgId}, which is {@code "org:"} followed by the organization's UUID (for example
 * {@code org:3f2a6c1e-8b7d-4c52-9a10-5d0b7e4f1a22}). The id is permanent; a rename never changes it.
 */
public interface KingdomView {
    /** The top level organization (kingdom) the player belongs to, if any. */
    Optional<String> kingdomOf(UUID player);

    /** The Lord of an organization, if the office is filled. */
    Optional<UUID> lordOf(String orgId);

    String displayName(String orgId);

    Collection<String> orgIds();
}
