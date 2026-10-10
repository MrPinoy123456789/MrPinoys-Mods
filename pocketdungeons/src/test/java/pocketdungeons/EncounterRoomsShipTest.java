package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Code audit 2026-10-10: {@link RoomSelector#ENCOUNTER_ROOMS} names rooms by their content id, and
 * {@code RubbleRulesTest} only proves each still has a handler in the sources. This ties the list to the
 * room manifests: each id must be the {@code content} of a shipped room, or sit in {@link #RETIRED}
 * with the reason it is allowed to linger (its handler stays for the situation tests). The same holds for
 * the other room id lists in the code: {@link PressureSources#SPUR_ROOMS} (RoomSelector has no further
 * special case list of room ids; its other sets name tags, not rooms).
 */
public class EncounterRoomsShipTest {

    /** Content ids whose manifest was retired on purpose. */
    private static final Set<String> RETIRED = Set.of(
            "sensor_gallery"); // PD-183: replaced by the Hush Gallery; the spec and handler stay

    private static final Path ROOMS = Paths.get("src/main/resources/data/pocketdungeons");

    public static void main(String[] args) throws IOException {
        StringBuilder manifests = new StringBuilder();
        for (String dir : new String[]{"dungeon_room", "anomaly_room"}) {
            try (Stream<Path> walk = Files.walk(ROOMS.resolve(dir))) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                    manifests.append(Files.readString(file)).append('\n');
                }
            }
        }
        String all = manifests.toString();
        checkShips("ENCOUNTER_ROOMS", RoomSelector.ENCOUNTER_ROOMS, all);
        checkShips("PressureSources.SPUR_ROOMS", PressureSources.SPUR_ROOMS, all);
        System.out.println("EncounterRoomsShipTest passed (" + RoomSelector.ENCOUNTER_ROOMS.size() + " encounter ids, "
                + PressureSources.SPUR_ROOMS.size() + " spur ids)");
    }

    private static void checkShips(String list, Set<String> ids, String manifests) {
        for (String id : ids) {
            boolean ships = manifests.contains("\"content\": \"" + id + "\"")
                    || manifests.contains("\"content\":\"" + id + "\"");
            check(ships || RETIRED.contains(id),
                    id + " is in " + list + " but no shipped room has that content id (retire it on purpose in RETIRED)");
            check(!(ships && RETIRED.contains(id)), id + " is listed as retired but a room ships it");
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
