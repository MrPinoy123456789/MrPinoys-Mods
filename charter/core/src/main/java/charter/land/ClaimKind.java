package charter.land;

/** What a claim is part of (PLAN.md §2.2). A claim takes the kind of the core it is assigned to. */
public enum ClaimKind {
    /** The chunk holding a keep's nexus. */
    KEEP_CORE,
    /** One of the eight chunks around a keep's core chunk. */
    KEEP_RING,
    /** A camp's banner chunk, or a chunk assigned to a camp. */
    CAMP,
    /** Farms, roads, mines: land assigned to a keep, or to no core while contested. */
    OUTLYING
}
