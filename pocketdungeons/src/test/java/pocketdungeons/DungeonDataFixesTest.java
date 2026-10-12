package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Roadmap T13 data fixes, read from the source tree: Deepslate is four floors deep on every path, and the two
 * resource-poor dungeons bury what their names promise (Lush Caves sand and clay, Copper Works coal and iron).
 * Pure JDK plus Gson.
 */
public class DungeonDataFixesTest {

    private static final Path DUNGEONS = Paths.get("src/main/resources/data/pocketdungeons/dungeon");

    public static void main(String[] args) throws IOException {
        testDeepslateIsFourFloors();
        testHiddenOre();
        System.out.println("DungeonDataFixesTest passed");
    }

    private static void testDeepslateIsFourFloors() throws IOException {
        JsonObject d = read("deepslate");
        Map<String, Integer> layer = new HashMap<>();
        String first = null;
        String last = null;
        for (JsonElement e : d.getAsJsonArray("nodes")) {
            JsonObject n = e.getAsJsonObject();
            String id = n.get("id").getAsString();
            layer.put(id, n.get("layer").getAsInt());
            if (n.get("layer").getAsInt() == 1) {
                first = id;
            }
            if (n.has("final") && n.get("final").getAsBoolean()) {
                last = id;
            }
        }
        check(!layer.containsKey("pale_gallery") && !layer.containsKey("collapsed_landing"),
                "the two dropped floors are gone");
        check(layer.size() == 7, "Deepslate has seven floors, got " + layer.size());
        check(last != null && layer.get(last) == 4, "the final floor is layer 4");
        Map<String, List<String>> next = new HashMap<>();
        for (JsonElement e : d.getAsJsonArray("edges")) {
            JsonObject edge = e.getAsJsonObject();
            String from = edge.get("from").getAsString();
            String to = edge.get("to").getAsString();
            check(layer.containsKey(from) && layer.containsKey(to), "edge names a missing node: " + from + " to " + to);
            check(layer.get(to) == layer.get(from) + 1, "edge skips a layer: " + from + " to " + to);
            next.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            check(!edge.has("lives"), "no door costs lives: the loader needs a free exit from every floor, and a"
                    + " second edge to the lone final floor is a duplicate");
        }
        List<Integer> lengths = new ArrayList<>();
        walk(first, last, next, 1, lengths);
        check(!lengths.isEmpty(), "the final floor is reachable");
        for (int len : lengths) {
            check(len == 4, "every path is exactly 4 floors, found one of " + len);
        }
        for (String id : layer.keySet()) {
            if (layer.get(id) == 3) {
                check(next.getOrDefault(id, List.of()).equals(List.of(last)), id + " leads straight to the final floor");
            }
        }
        boolean landing = false;
        for (JsonElement e : d.getAsJsonArray("nodes")) {
            JsonObject n = e.getAsJsonObject();
            if (n.has("roomBias") && n.get("id").getAsString().equals("tuff_cellars")) {
                for (JsonElement r : n.getAsJsonArray("roomBias")) {
                    landing |= r.getAsString().equals("deep_dark_landing");
                }
            }
        }
        check(landing, "deep_dark_landing is biased on Tuff Cellars");
    }

    private static void walk(String at, String last, Map<String, List<String>> next, int floors, List<Integer> out) {
        if (at.equals(last)) {
            out.add(floors);
            return;
        }
        for (String to : next.getOrDefault(at, List.of())) {
            walk(to, last, next, floors + 1, out);
        }
    }

    private static void testHiddenOre() throws IOException {
        List<String> lush = blocks("rootworks");
        check(lush.contains("sand") && lush.contains("clay"), "Lush Caves buries sand and clay: " + lush);
        List<String> copper = blocks("copper_works");
        check(copper.contains("coal_ore") && copper.contains("iron_ore"), "Copper Works buries coal and iron: " + copper);
        check(copper.stream().noneMatch(b -> b.contains("copper")), "copper ore left the game (K2.8): " + copper);
        for (String id : Set.of("rootworks", "copper_works")) {
            JsonObject h = read(id).getAsJsonObject("hiddenOre");
            check(h.get("pocketsMin").getAsInt() >= 1 && h.get("pocketsMax").getAsInt() <= 2,
                    id + ": one to two pockets");
        }
    }

    private static List<String> blocks(String dungeon) throws IOException {
        JsonObject h = read(dungeon).getAsJsonObject("hiddenOre");
        check(h != null, dungeon + " has hiddenOre");
        List<String> out = new ArrayList<>();
        JsonArray arr = h.getAsJsonArray("blocks");
        for (JsonElement e : arr) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static JsonObject read(String id) throws IOException {
        try (Reader r = Files.newBufferedReader(DUNGEONS.resolve(id + ".json"))) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
