package pocketdungeons;

/**
 * What a floor's pad gate asks for (dungeon structure: node {@code objective}
 * on {@link DungeonDef.Node}). {@link #SPAWNERS} is the ordinary clear: a
 * fraction of the floor's trial spawners. The other kinds place their own
 * goal on the floor; {@link FloorObjectives} dispatches on this, and the
 * per-kind placement, gating and use logic live on its
 * {@link FloorObjectives.ObjectiveState}.
 */
enum FloorObjective {

    SPAWNERS, HEIST, CLAIM, HAUNT;

    /** The kind a node's {@code objective} word names; {@code null} and strangers read as spawners. */
    static FloorObjective of(String word) {
        return switch (word == null ? "" : word) {
            case DungeonDef.Node.OBJECTIVE_HEIST -> HEIST;
            case DungeonDef.Node.OBJECTIVE_CLAIM -> CLAIM;
            case DungeonDef.Node.OBJECTIVE_HAUNT -> HAUNT;
            default -> SPAWNERS;
        };
    }
}
