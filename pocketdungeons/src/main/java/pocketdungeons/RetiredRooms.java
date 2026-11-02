package pocketdungeons;

import java.util.Set;

/**
 * Rooms the owner has retired from the dungeon pool. A retired room stays loaded
 * (its manifest, template, spec and handler all remain, so a saved dungeon that
 * already holds one still stamps and plays, and a gametest can stamp it by id),
 * but {@link RoomManifest#queryAnyRotation} never offers it to the planner, so
 * no new dungeon generates it. To bring one back, delete its id here.
 *
 * <p>2026-11-02: the four "return chest" rooms, each a bank of hoppers whose door
 * opens when an item is put in a chest. They did not play the way the design
 * intended (see docs/DECISIONS.md, 2026-11-02).
 */
final class RetiredRooms {

    /** Bare manifest ids (the {@code pocketdungeons} namespace is implied). */
    static final Set<String> IDS = Set.of("frame_lock", "item_plate", "flow_puzzle", "sorting_floor");

    private RetiredRooms() {
    }

    /** Whether a room name, bare or {@code pocketdungeons:} qualified, is retired. */
    static boolean isRetired(String name) {
        if (name == null) {
            return false;
        }
        String prefix = PocketDungeonsMod.MOD_ID + ":";
        return IDS.contains(name.startsWith(prefix) ? name.substring(prefix.length()) : name);
    }
}
