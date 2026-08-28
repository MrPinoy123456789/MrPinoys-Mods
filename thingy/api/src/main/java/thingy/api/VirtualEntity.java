package thingy.api;

import java.util.UUID;

/**
 * One live virtual entity: a vanilla entity (the carrier) carrying an
 * identity and owner Thingy tracks across restarts (PLAN.md Phase 6).
 *
 * <p>Unlike {@link VirtualItem}, this interface does not try to generalize a
 * kind's own state (a kamutotems boss's tier, roll, kamu, and aura have
 * nothing in common with a hypothetical dungeon entity's state). It answers
 * only the three questions every consumer needs regardless of kind: what kind
 * is this, which vanilla entity carries it, and who owns it. A consumer that
 * wants kind-specific state looks the concrete type up itself, the same way
 * {@code AuraHost} already does for kamutotems bosses.
 */
public interface VirtualEntity {

    /** Namespaced kind, e.g. {@code "kamutotems:boss"}. */
    String kind();

    /** The carrier entity's UUID: the join key between a live entity and this identity. */
    UUID entityId();

    /** The player UUID this virtual entity belongs to. */
    UUID owner();
}
