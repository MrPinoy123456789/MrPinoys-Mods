package charter.land;

import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;

/**
 * A keep or a camp: the root land is counted from (DESIGN.md §9). Siege state,
 * food and drains belong to later milestones and are kept elsewhere, keyed by
 * {@link #id}.
 */
public final class Core {

    public final CoreId id;
    public final CoreKind kind;
    public final OrgId owner;
    /** The core chunk of a keep, or the banner chunk of a camp. */
    public final ChunkKey center;
    /** Placement order across the world; the lower number is the older core and wins hop ties. */
    public final long order;
    /** Wall time it was placed. */
    public final long placedAt;

    private CampPhase phase;
    /** Diamonds the owner paid to place this outpost; held by the outpost, not the treasury. Zero for a keep. */
    public long deposit;

    /** Siege time the current phase began. */
    private long phaseSince;

    public Core(CoreId id, CoreKind kind, OrgId owner, ChunkKey center, long order, long placedAt,
                CampPhase phase, long phaseSince) {
        this.id = id;
        this.kind = kind;
        this.owner = owner;
        this.center = center;
        this.order = order;
        this.placedAt = placedAt;
        this.phase = phase;
        this.phaseSince = phaseSince;
    }

    public boolean isKeep() {
        return kind == CoreKind.KEEP;
    }

    /** A keep's phase is always ACTIVE. */
    public CampPhase phase() {
        return phase;
    }

    public long phaseSince() {
        return phaseSince;
    }

    public void setPhase(CampPhase newPhase, long siegeNow) {
        if (isKeep()) throw new IllegalStateException("a keep has no phase");
        phase = newPhase;
        phaseSince = siegeNow;
    }

    public boolean activeCamp() {
        return kind == CoreKind.CAMP && phase == CampPhase.ACTIVE;
    }
}
