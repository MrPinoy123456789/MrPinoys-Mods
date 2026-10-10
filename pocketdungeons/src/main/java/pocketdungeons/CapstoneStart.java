package pocketdungeons;

/**
 * Dungeon structure W7b (design D16a), reworked by J3 (plan 2026-10-06-2):
 * the capstone's final floor used to open with omen on it; with omen now the
 * trip's lives it opens two levels harder instead. Pure Java: no Minecraft
 * imports.
 */
final class CapstoneStart {

    private CapstoneStart() {}

    /** The levels a capstone's final floor fights above the run's level (J3). */
    static final int CAPSTONE_LEVEL_BONUS = 2;

    /**
     * The level head start of the floor at {@code nodeId} of {@code def}:
     * {@link #CAPSTONE_LEVEL_BONUS} on the final node of a capstone dungeon,
     * 0 on every other floor.
     */
    static int levelBonus(DungeonDef def, String nodeId) {
        if (def == null || def.kind() != DungeonDef.Kind.CAPSTONE || !TripDoors.isFinal(def, nodeId)) {
            return 0;
        }
        return CAPSTONE_LEVEL_BONUS;
    }
}
