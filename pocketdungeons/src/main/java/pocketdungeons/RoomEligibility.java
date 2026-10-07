package pocketdungeons;

import java.util.List;

/**
 * Dungeon structure W5 (design D6a, section 7): which rooms a floor may draw, read from the room
 * metadata W4 parsed ({@code dungeons}, legacy {@code theme}, {@code acts}, {@code borrowableBy},
 * {@code graphRole}). Pure Java: {@link RoomSelector} calls it for every candidate, and a headless
 * test pins it. A room with none of the new fields behaves exactly as before: it matches by its
 * legacy {@code theme} list (empty matches everything).
 *
 * <p>A room is eligible for a floor when any of these holds:
 * <ol>
 *   <li>its {@code dungeons} (or, when that is empty, legacy {@code theme}) names the floor's
 *       dungeon, its main theme or the floor's room theme;</li>
 *   <li>it names no dungeon or theme and its {@code acts} is empty or holds the dungeon's act;</li>
 *   <li>(D6a) the floor's node theme is borrowed and the room names the dungeon that owns that
 *       theme, or the borrowed theme itself;</li>
 *   <li>its {@code borrowableBy} lists the floor's dungeon.</li>
 * </ol>
 * On top of that, {@code graphRole} gates and weights: {@code final} rooms appear only on a
 * final node, {@code capstone} rooms only on the final node of a capstone dungeon, {@code entry}
 * rooms are weighted up on the entry node and {@code side_reward} rooms on a floor reached by a
 * side edge.
 */
final class RoomEligibility {

    private RoomEligibility() {}

    /** The weight multiplier for a room whose graph role suits the floor (preference, not exclusion). */
    static final int ROLE_BOOST = 3;

    /**
     * PD-149: the weight multiplier for a room bound to the floor's own dungeon or main theme
     * ({@code dungeons}, or legacy {@code theme}, names it). A preference again, not a lock, but a
     * stronger one than {@link #ROLE_BOOST}: themed rooms should carry a floor, with generic halls
     * filling the cells they do not cover. Without it a node dungeon could roll a whole floor
     * of generic halls and pay nothing (playtest 2026-10-05-1).
     */
    static final int DUNGEON_BOOST = 5;

    /**
     * The floor a room is chosen for.
     *
     * @param dungeonId           the floor dungeon's id
     * @param mainTheme           the dungeon's main theme id
     * @param roomTheme           the requested room theme the planner was given (the floor theme's room theme)
     * @param act                 the dungeon's act, or 0 when unknown
     * @param capstone            whether the dungeon is a capstone dungeon
     * @param floorTheme          the floor's node theme id (equals the main theme unless borrowed)
     * @param borrowedFromDungeon the dungeon that owns a borrowed floor theme, or empty when not borrowed
     * @param entryNode           whether the floor is the dungeon's entry node
     * @param finalNode           whether the floor is a final node
     * @param sideEdge            whether the floor was reached by a side edge (a shard cost)
     * @param minNodeRooms        how many node-bearing rooms the floor must place (the PD-149
     *                            guarantee as data, {@code DungeonDef.minNodeRooms}); 0 means none
     */
    record Floor(String dungeonId, String mainTheme, String roomTheme, int act, boolean capstone,
                 String floorTheme, String borrowedFromDungeon, boolean entryNode, boolean finalNode,
                 boolean sideEdge, int minNodeRooms) {

        /** The pre-guarantee shape, kept so callers written before the knob read the same. */
        Floor(String dungeonId, String mainTheme, String roomTheme, int act, boolean capstone,
              String floorTheme, String borrowedFromDungeon, boolean entryNode, boolean finalNode,
              boolean sideEdge) {
            this(dungeonId, mainTheme, roomTheme, act, capstone, floorTheme, borrowedFromDungeon,
                    entryNode, finalNode, sideEdge, 0);
        }

        boolean borrowed() {
            return borrowedFromDungeon != null && !borrowedFromDungeon.isEmpty();
        }
    }

    /** The room metadata this class reads, free of Minecraft types. */
    record RoomTags(List<String> dungeons, List<String> theme, List<Integer> acts, List<String> graphRole,
                    List<String> borrowableBy) {
        static RoomTags of(DungeonRoomMeta meta) {
            return new RoomTags(meta.dungeons, meta.theme, meta.acts, meta.graphRole, meta.borrowableBy);
        }
    }

    /** Whether the room may be drawn for the floor. A null floor means no floor context: always true. */
    static boolean eligible(RoomTags room, Floor floor) {
        if (floor == null) {
            return true;
        }
        return themeEligible(room, floor) && roleAllowed(room, floor);
    }

    /** The dungeon, theme and act rules (1 to 4 above). */
    static boolean themeEligible(RoomTags room, Floor floor) {
        List<String> names = room.dungeons().isEmpty() ? room.theme() : room.dungeons();
        if (names.isEmpty()) {
            return room.acts().isEmpty() || room.acts().contains(floor.act());
        }
        if (containsAny(names, floor.dungeonId(), floor.mainTheme(), floor.roomTheme())) {
            return true;
        }
        if (floor.borrowed() && containsAny(names, floor.borrowedFromDungeon(), floor.floorTheme())) {
            return true;
        }
        return containsAny(room.borrowableBy(), floor.dungeonId());
    }

    /** The {@code graphRole} gate: a room with no roles, or any role that fits the floor, is allowed. */
    static boolean roleAllowed(RoomTags room, Floor floor) {
        if (room.graphRole().isEmpty()) {
            return true;
        }
        for (String role : room.graphRole()) {
            switch (role) {
                case "final" -> {
                    if (floor.finalNode()) {
                        return true;
                    }
                }
                case "capstone" -> {
                    if (floor.finalNode() && floor.capstone()) {
                        return true;
                    }
                }
                case "entry", "any", "side_reward" -> {
                    return true;
                }
                default -> {
                    // An unknown word is the validator's to report; it does not lock the room out.
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Dungeon structure W7a: on the final floor of a capstone dungeon the terminal cell (the one
     * asked for with the structural {@code exit} role) holds the boss room, not the plain exit
     * hall. When at least one candidate carries the {@code capstone} graph role, only those are
     * kept; a capstone dungeon with no such room (the Drowned Vault, whose warden stands in the
     * ordinary exit hall) keeps every candidate, and so does every other floor and role.
     */
    static <T> List<T> narrowToCapstone(List<T> candidates, java.util.function.Function<T, RoomTags> tags,
                                        Floor floor, boolean exitRole) {
        if (floor == null || !floor.finalNode() || !floor.capstone() || !exitRole) {
            return candidates;
        }
        List<T> bosses = new java.util.ArrayList<>();
        for (T candidate : candidates) {
            if (tags.apply(candidate).graphRole().contains("capstone")) {
                bosses.add(candidate);
            }
        }
        return bosses.isEmpty() ? candidates : bosses;
    }

    /**
     * The draw weight multiplier for this room on this floor: {@link #DUNGEON_BOOST} when the room
     * belongs to the floor's dungeon or main theme (PD-149), times {@link #ROLE_BOOST} when an
     * entry room is on the entry node or a side_reward room is on a side-edge floor. 1 for a room
     * with no particular claim.
     */
    static int weightFactor(RoomTags room, Floor floor) {
        if (floor == null) {
            return 1;
        }
        int factor = 1;
        if (boundTo(room, floor)) {
            factor *= DUNGEON_BOOST;
        }
        if (floor.entryNode() && room.graphRole().contains("entry")) {
            factor *= ROLE_BOOST;
        }
        if (floor.sideEdge() && room.graphRole().contains("side_reward")) {
            factor *= ROLE_BOOST;
        }
        return factor;
    }

    /**
     * Whether the room belongs to the floor's own dungeon or main theme: its {@code dungeons}
     * (or legacy {@code theme}) names the dungeon id or the main theme. A room eligible only
     * through a borrowed theme or {@code borrowableBy} does not count, so a deviating floor's
     * borrowed rooms do not crowd out the dungeon's own.
     */
    static boolean boundTo(RoomTags room, Floor floor) {
        List<String> names = room.dungeons().isEmpty() ? room.theme() : room.dungeons();
        return containsAny(names, floor.dungeonId(), floor.mainTheme());
    }

    private static boolean containsAny(List<String> ids, String... wanted) {
        for (String id : ids) {
            String q = DungeonDef.qualify(id);
            for (String w : wanted) {
                if (w != null && !w.isEmpty() && q.equals(DungeonDef.qualify(w))) {
                    return true;
                }
            }
        }
        return false;
    }
}
