package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * PD-218: {@code LayoutStamper.applyWindowBand} drops a whole band when one room of an edge sets window
 * {@code none} and its neighbour sets a material. The rule is right (half a band is glass backed by stone);
 * the fault was data, so this walks the shipped manifests instead. For every dungeon it builds the pool of
 * rooms that may be drawn for it (the same dungeon, theme and act gate the planner uses, ignoring the
 * graph role gate so the pool is a superset), and fails on any pair in that pool where exactly one room
 * is {@code none}. Any two rooms of a pool can end up on one edge, so a disagreement is a band the player
 * loses on some seed.
 */
public class WindowBandAgreementTest {

    /**
     * Rooms outside this task's ownership that still disagree with their pool: final floor vaults that ship
     * {@code none} beside the shared halls (filed as a follow-up under PD-218). Listed so the test guards every
     * other room today; the test also fails when an entry stops being {@code none}, so the list only shrinks.
     */
    private static final Set<String> KNOWN_OPEN = Set.of("admirals_vault", "seam_heart");

    private static final Path DATA = Paths.get("src/main/resources/data/pocketdungeons");

    public static void main(String[] args) throws IOException {
        TreeMap<String, DungeonRoomMeta> rooms = new TreeMap<>();
        try (Stream<Path> walk = Files.list(DATA.resolve("dungeon_room"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString().replace(".json", "");
                JsonObject obj = JsonParser.parseString(stripBom(Files.readString(file))).getAsJsonObject();
                rooms.put(name, DungeonRoomMeta.fromJson(obj, name));
            }
        }
        for (String open : KNOWN_OPEN) {
            check(rooms.containsKey(open) && DungeonRoomMeta.WINDOW_NONE.equals(rooms.get(open).window),
                    open + " is in KNOWN_OPEN but no longer sets window none; remove it from the list");
        }
        check(rooms.size() > 100, "expected the shipped room manifests, found " + rooms.size());
        List<String> problems = new ArrayList<>();
        int dungeons = 0;
        int pairs = 0;
        try (Stream<Path> walk = Files.list(DATA.resolve("dungeon"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                String id = file.getFileName().toString().replace(".json", "");
                JsonObject obj = JsonParser.parseString(stripBom(Files.readString(file))).getAsJsonObject();
                if ("proving".equals(obj.has("kind") ? obj.get("kind").getAsString() : "")) {
                    continue; // one fixed room per floor, no neighbours to disagree with
                }
                int act = obj.has("act") ? obj.get("act").getAsInt() : 0;
                String mainTheme = obj.has("mainTheme") ? obj.get("mainTheme").getAsString() : id;
                RoomEligibility.Floor floor = new RoomEligibility.Floor("pocketdungeons:" + id, mainTheme,
                        mainTheme, act, false, mainTheme, "", false, false, false);
                List<String> none = new ArrayList<>();
                List<String> material = new ArrayList<>();
                for (var entry : rooms.entrySet()) {
                    if (!RoomEligibility.themeEligible(RoomEligibility.RoomTags.of(entry.getValue()), floor)) {
                        continue;
                    }
                    if (KNOWN_OPEN.contains(entry.getKey())) {
                        continue;
                    }
                    if (DungeonRoomMeta.WINDOW_NONE.equals(entry.getValue().window)) {
                        none.add(entry.getKey());
                    } else {
                        material.add(entry.getKey());
                    }
                }
                dungeons++;
                pairs += none.size() * material.size();
                if (!none.isEmpty() && !material.isEmpty()) {
                    problems.add(id + ": window none in " + none + " but a material in " + material);
                }
            }
        }
        check(dungeons > 20, "expected the shipped dungeons, found " + dungeons);
        if (!problems.isEmpty()) {
            throw new AssertionError("PD-218: rooms that can share an edge disagree on window \"none\" ("
                    + pairs + " pairs); set both sides to none or give the none side a material:\n  "
                    + String.join("\n  ", problems));
        }
        System.out.println("WindowBandAgreementTest passed (" + dungeons + " dungeons, " + rooms.size() + " rooms)");
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
