package thingy.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-mod lookup of live virtual entities by their carrier's UUID.
 *
 * <p>Shaped like {@link VirtualTag}'s namespace registry, not like
 * {@link VirtualItems}'s single-owner registry: {@code VirtualItems} has
 * exactly one implementation, Thingy's own item registry, but a virtual
 * entity is owned and tracked by whichever mod defines it (kamutotems for
 * bosses, potentially others later). Each owning mod registers its own
 * {@link Lookup} under its own namespace; {@link #byEntity} asks every
 * registered lookup in turn.
 *
 * <p>This does not replace a mod's own persistence. kamutotems' boss records
 * (PLAN.md Phase 0's {@code BossRecords}) are the source of truth for boss
 * identity and continue to be, unchanged; registering a {@link Lookup} here
 * only exposes what {@code BossHost} already tracks in memory to callers
 * outside kamutotems, the way {@code AuraHost} already reads it from inside
 * (PLAN.md Phase 6, "absorbed by reference, not by migration").
 */
public final class VirtualEntities {

    /** One namespace's answer to "is this entity UUID one of mine, and if so, what is it." */
    public interface Lookup {
        Optional<VirtualEntity> byEntity(UUID entityId);
    }

    private static final Map<String, Lookup> LOOKUPS = new LinkedHashMap<>();

    private VirtualEntities() {}

    /**
     * Registers the lookup for a namespace. Call once, at that namespace's
     * registration time. A second registration for the same namespace fails
     * fast and loud, naming both callers, matching {@link VirtualTag#register}.
     */
    public static synchronized void register(String namespace, Lookup lookup) {
        Lookup existing = LOOKUPS.putIfAbsent(namespace, lookup);
        if (existing != null) {
            throw new IllegalStateException(
                    "Namespace '" + namespace + "' is already registered with a VirtualEntities lookup");
        }
    }

    /** The virtual entity carried by this UUID, checked against every registered namespace. */
    public static Optional<VirtualEntity> byEntity(UUID entityId) {
        if (entityId == null) {
            return Optional.empty();
        }
        for (Lookup lookup : LOOKUPS.values()) {
            Optional<VirtualEntity> found = lookup.byEntity(entityId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
