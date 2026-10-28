package pocketdungeons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vertical floors phase 2: the synthetic manifest and role fixtures the stair tests share. The generic rooms are
 * the ones {@code LayoutFingerprintTest} uses; the stair rooms are four two-story rooms whose masks put the way
 * in on the top story (west at rotation 0) and the way on one story down (east, north or south).
 */
final class StairTestSupport {

    private StairTestSupport() {}

    /** Door mask of a stair room at rotation 0: in on the west, on through {@code lower}. */
    static int stairMask(DoorMask.Direction lower) {
        return DoorMask.edge(DoorMask.Direction.WEST, 0) | DoorMask.edge(lower, 1);
    }

    static RoomManifest manifest(boolean withStairs) {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        entries.addAll(everyMask("passage", List.of("corridor"), List.of(), List.of()));
        entries.addAll(everyMask("plain_fight", List.of("encounter"), List.of(), List.of()));
        entries.addAll(everyMask("hoard", List.of("loot"), List.of(), List.of()));
        entries.addAll(everyMask("spring", List.of("loot", "corridor"), List.of(SituationTags.WATER), List.of()));
        entries.addAll(everyMask("workshop", List.of("loot"), List.of(SituationTags.REDSTONE), List.of()));
        entries.addAll(everyMask("quarry", List.of("corridor"), List.of(SituationTags.BLOCKS), List.of()));
        entries.addAll(everyMask("pot_room", List.of("loot"), List.of(SituationTags.TRIAL_KEY), List.of()));
        entries.addAll(everyMask("iron_door", List.of("encounter"), List.of(),
                List.of(SituationTags.REDSTONE), DungeonRoomMeta.ACCESS_GATED, 6));
        entries.addAll(everyMask("flow_puzzle", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("pump_room", List.of("loot"), List.of(),
                List.of(SituationTags.WATER, SituationTags.REDSTONE), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("soft_wall", List.of("corridor"), List.of(),
                List.of(SituationTags.BLOCKS), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("barred_vault", List.of("corridor"), List.of(),
                List.of(SituationTags.TRIAL_KEY), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("unreachable_dock", List.of("encounter", "loot", "corridor"),
                List.of(), List.of(SituationTags.BOAT), DungeonRoomMeta.ACCESS_OPEN, 20));
        if (withStairs) {
            entries.add(stair("stair_straight", DoorMask.Direction.EAST));
            entries.add(stair("stair_gallery", DoorMask.Direction.EAST));
            entries.add(stair("stair_turn_north", DoorMask.Direction.NORTH));
            entries.add(stair("stair_turn_south", DoorMask.Direction.SOUTH));
        }
        return RoomManifest.create(entries, List.of());
    }

    private static RoomManifest.Entry stair(String name, DoorMask.Direction lower) {
        DungeonRoomMeta meta = new DungeonRoomMeta(name, 1, 1, qualified(List.of("corridor")), 1, 0, -1, null,
                List.of(), null, 1, List.of(), List.of(), null, DungeonRoomMeta.ACCESS_OPEN,
                DungeonRoomMeta.WINDOW_BARS, 2);
        return new RoomManifest.Entry(name, meta, stairMask(lower));
    }

    private static List<RoomManifest.Entry> everyMask(String name, List<String> roles, List<String> provides,
                                                      List<String> requires) {
        return everyMask(name, roles, provides, requires, DungeonRoomMeta.ACCESS_OPEN, 1);
    }

    private static List<RoomManifest.Entry> everyMask(String name, List<String> roles, List<String> provides,
                                                      List<String> requires, String access, int weight) {
        List<RoomManifest.Entry> out = new ArrayList<>();
        for (int mask = 1; mask <= 15; mask++) {
            DungeonRoomMeta meta = new DungeonRoomMeta(name, 1, 1, qualified(roles), weight, 0, -1, null,
                    List.of(), null, 1, provides, requires, null, access, DungeonRoomMeta.WINDOW_BARS, 1);
            out.add(new RoomManifest.Entry(name + "_" + DoorMask.toLetters(mask), meta, mask));
        }
        return out;
    }

    private static List<String> qualified(List<String> roles) {
        List<String> out = new ArrayList<>();
        for (String role : roles) {
            String resolved = RoleIds.resolve(role);
            out.add(resolved == null ? role : resolved);
        }
        return List.copyOf(out);
    }

    static void publishSyntheticRoleManifest() {
        Map<String, RoleManifest.Entry> entries = new LinkedHashMap<>();
        Object[][] roles = {
                {RoleIds.ENCOUNTER, 45, RoomRoleDefinition.Operation.TRIAL_ENCOUNTER},
                {RoleIds.LOOT, 25, RoomRoleDefinition.Operation.TOOL_CACHE},
                {RoleIds.CORRIDOR, 30, RoomRoleDefinition.Operation.NONE},
        };
        for (Object[] r : roles) {
            String id = (String) r[0];
            RoomRoleDefinition def = new RoomRoleDefinition(id, "interior", (int) r[1], 0, -1, List.of(),
                    (RoomRoleDefinition.Operation) r[2]);
            entries.put(id, new RoleManifest.Entry(id, def));
        }
        RoleManifest.publish(RoleManifest.create(entries, List.of()));
    }

    static List<RoomRoleDefinition> builtInRoles() {
        return List.of(
                new RoomRoleDefinition(RoleIds.ENCOUNTER, "interior", 45, 0, -1, List.of(),
                        RoomRoleDefinition.Operation.TRIAL_ENCOUNTER),
                new RoomRoleDefinition(RoleIds.LOOT, "interior", 25, 0, -1, List.of(),
                        RoomRoleDefinition.Operation.TOOL_CACHE),
                new RoomRoleDefinition(RoleIds.CORRIDOR, "interior", 30, 0, -1, List.of(),
                        RoomRoleDefinition.Operation.NONE));
    }
}
