package pocketdungeons;

/**
 * Dungeon structure W7b (design D16a): "a capstone floor starts with omen on it". The final floor
 * of every capstone dungeon opens with {@link PocketDungeonsConfig#capstoneStartOmen()} omen
 * (default 1), applied on the same path as a zone's head start. Pure Java: no Minecraft imports.
 */
final class CapstoneStart {

    private CapstoneStart() {}

    /**
     * The omen the floor at {@code nodeId} of {@code def} starts with: {@code configured} (clamped to
     * 0 to {@link Omen#MAX_OMEN}) on the final node of a capstone dungeon, 0 on every other floor.
     */
    static int amount(DungeonDef def, String nodeId, int configured) {
        if (def == null || def.kind() != DungeonDef.Kind.CAPSTONE || !TripDoors.isFinal(def, nodeId)) {
            return 0;
        }
        return Omen.clamp(configured);
    }

    /** The floor's omen once the head start lands: never lowered, raised to {@code amount} when below it. */
    static int raise(int current, int amount) {
        return Omen.clamp(Math.max(current, amount));
    }
}
