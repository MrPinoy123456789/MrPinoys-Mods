package pocketdungeons;

/**
 * Read only questions about the dungeon trip a record is on (dungeon structure
 * W2), so screens, titles and the lifecycle ask in one place. The trip state
 * itself lives on {@link IntervalState}.
 */
final class TripView {

    private TripView() {}

    /** The dungeon this trip is playing, or {@code null} before the first door and in a Mine. */
    static DungeonDef def(InstanceRecord record) {
        if (record == null || record.interval.dungeonId.isEmpty()) {
            return null;
        }
        return DungeonDefs.current().byId(record.interval.dungeonId);
    }

    /** The dungeon's display name, or an empty string with no dungeon. */
    static String dungeonName(InstanceRecord record) {
        DungeonDef def = def(record);
        return def == null ? "" : def.name();
    }

    /** Whether the trip stands on a node whose every door leads to a final floor. */
    static boolean finalAhead(InstanceRecord record) {
        DungeonDef def = def(record);
        return def != null && !record.interval.finished && TripDoors.finalAhead(def, record.interval.nodeId);
    }

    /** Whether the trip's current node is the final floor of its dungeon. */
    static boolean onFinal(InstanceRecord record) {
        DungeonDef def = def(record);
        return def != null && TripDoors.isFinal(def, record.interval.nodeId);
    }
}
