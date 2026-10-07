package pocketdungeons;

/**
 * What level a floor runs at (design D26, step 3a of the 2026-10-06
 * simplification). The dungeon's {@code baseLevel} sets the entry floor; each
 * layer below defaults to one deeper, and a node may pin its own
 * {@code level} when the authored shape wants a sharper jump. The rule is
 * pure arithmetic on the definition, so every surface (door offers, floor
 * pay, the validator) reads the same number.
 *
 * <p>A floor's level is a property of the dungeon, not of the player:
 * over-level members get emeralds instead of scrap ({@link FloorPay}), never
 * a lower floor level.
 */
final class FloorLevels {

    private FloorLevels() {}

    /**
     * The level a floor of {@code nodeId} runs at: the node's declared
     * {@code level}, else {@code baseLevel + layer - 1}. An unknown node id
     * falls back to {@code baseLevel} so a stale reference still produces a
     * level rather than a crash.
     */
    static int of(DungeonDef def, String nodeId) {
        DungeonDef.Node node = def == null ? null : def.node(nodeId);
        return of(def, node);
    }

    /** {@link #of(DungeonDef, String)} for a node already in hand. */
    static int of(DungeonDef def, DungeonDef.Node node) {
        int base = def == null ? 1 : def.baseLevel();
        if (node == null) {
            return base;
        }
        if (node.level() != DungeonDef.Node.UNSET_LEVEL) {
            return node.level();
        }
        return base + node.layer() - 1;
    }
}
