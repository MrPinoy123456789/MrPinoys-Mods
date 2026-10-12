package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Dungeon structure W6: the Cow Pits finite rule. Pure policy (the cap, the wheat refusal) and
 * the shipped data: the room metadata keeps the per floor total inside 6 to 10, and none of the
 * Cow Pits loot tables holds wheat, seeds, carrots or potatoes.
 */
public class CowPitsTest {

    private static final Set<String> FORBIDDEN = Set.of("minecraft:wheat", "minecraft:hay_block",
            "minecraft:potato", "minecraft:poisonous_potato", "minecraft:carrot", "minecraft:golden_carrot",
            "minecraft:baked_potato");

    public static void main(String[] args) throws Exception {
        testCap();
        testWheatRefused();
        testRoomMetadataMatchesRule();
        testLootTablesAreFiltered();
        testThreeFloors();
        testNoBorrowedRooms();
        System.out.println("CowPitsTest: all checks passed");
    }

    private static void testCap() {
        check(CowPits.FLOOR_MIN == 5 && CowPits.FLOOR_CAP == 21, "5 to 21 cows per floor (the parlour adds rooms)");
        check(CowPits.maxPossible() <= CowPits.FLOOR_CAP, "the room set never exceeds the cap: " + CowPits.maxPossible());
        check(CowPits.maxPossible() >= CowPits.FLOOR_MIN, "and can reach the minimum");
        check(CowPits.maxPossible() == 21, "pens 6 + loft 2 + yard 2 + ward 3 + stalls 3 + byre 2 + bay 3: "
                + CowPits.maxPossible());
        check(CowPits.ROOM_COWS.keySet().equals(CowPits.ROOM_LIMITS.keySet()), "every cow room has a limit");
        check(CowPits.ROOM_COWS.get("cow_ward") == 1 + CowPits.WARD_CELL_NAMES.size(), "ward: pedestal plus named cell cows");
        check(CowPits.ROOM_COWS.get("zero_bay") == 1 + CowPits.ZERO_CELL_NAMES.size(), "bay: pedestal plus attendants");
        check(CowPits.ROOM_COWS.get("milking_stalls") + CowPits.ROOM_COWS.get("specimen_byre") >= CowPits.FLOOR_MIN,
                "the parlour rooms alone reach the minimum");
        check(CowPits.ROOM_COWS.get("cow_pens") *CowPits.ROOM_LIMITS.get("cow_pens") >= CowPits.FLOOR_MIN,
                "the pens alone reach the minimum");
        check(CowPits.spawnCount(3, 0) == 3, "a room spawns its own count on an empty floor");
        check(CowPits.spawnCount(3, 19) == 2, "the cap trims the last room");
        check(CowPits.spawnCount(3, 21) == 0, "a full floor spawns none");
        check(CowPits.spawnCount(3, 24) == 0, "an over full floor spawns none");
        check(CowPits.spawnCount(0, 0) == 0, "an empty room spawns none");
    }

    private static void testWheatRefused() {
        check(CowPits.refusesFood(true), "wheat is refused");
        check(!CowPits.refusesFood(false), "other items are not touched");
    }

    /** Room files for the cow rooms agree with the rule's tables. */
    private static void testRoomMetadataMatchesRule() throws Exception {
        Path rooms = root().resolve("data/pocketdungeons/dungeon_room");
        for (String content : CowPits.ROOM_COWS.keySet()) {
            JsonObject room = read(rooms.resolve(content + ".json"));
            check(room.get("content").getAsString().equals(content), content + " names its content");
            check(room.get("maxPerDungeon").getAsInt() == CowPits.ROOM_LIMITS.get(content),
                    content + " limit matches CowPits.ROOM_LIMITS");
            check(room.get("dungeons").getAsJsonArray().get(0).getAsString().equals("cow_pits"),
                    content + " is a Cow Pits room");
        }
    }

    private static void testLootTablesAreFiltered() throws Exception {
        Path chests = root().resolve("data/pocketdungeons/loot_table/chests");
        int seen = 0;
        try (Stream<Path> files = Files.list(chests)) {
            for (Path file : (Iterable<Path>) files
                    .filter(p -> p.getFileName().toString().endsWith("_cow_pits.json"))::iterator) {
                seen++;
                scan(read(file), file.getFileName().toString());
            }
        }
        check(seen >= 12, "the Cow Pits tier and supply tables exist: " + seen);
    }

    /**
     * The Milking Parlour is floor two of exactly three: Pen Gate, the parlour, then the Big Barn
     * as the only final. Every root to final path walks all three layers.
     */
    private static void testThreeFloors() throws Exception {
        JsonObject dungeon = read(root().resolve("data/pocketdungeons/dungeon/cow_pits.json"));
        JsonObject nodes = new JsonObject();
        java.util.Map<String, Integer> layers = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<String>> edges = new java.util.HashMap<>();
        for (JsonElement el : dungeon.getAsJsonArray("nodes")) {
            JsonObject node = el.getAsJsonObject();
            nodes.add(node.get("id").getAsString(), node);
            layers.put(node.get("id").getAsString(), node.get("layer").getAsInt());
        }
        check(layers.size() == 3, "exactly three floors: " + layers.keySet());
        check(layers.get("milking_parlour") == 2, "the Milking Parlour is floor 2");
        int finals = 0;
        for (JsonElement el : dungeon.getAsJsonArray("nodes")) {
            JsonObject node = el.getAsJsonObject();
            if (node.has("final") && node.get("final").getAsBoolean()) {
                finals++;
                check(node.get("id").getAsString().equals("the_big_barn"), "the Big Barn is the final");
                check(node.get("layer").getAsInt() == 3, "the final sits on layer 3");
            }
        }
        check(finals == 1, "exactly one final");
        for (JsonElement el : dungeon.getAsJsonArray("edges")) {
            JsonObject edge = el.getAsJsonObject();
            edges.computeIfAbsent(edge.get("from").getAsString(), k -> new java.util.ArrayList<>())
                    .add(edge.get("to").getAsString());
        }
        int[] paths = {0};
        walk("pen_gate", 1, layers, edges, nodes, paths);
        check(paths[0] > 0, "the pen gate reaches a final");
    }

    private static void walk(String node, int depth, java.util.Map<String, Integer> layers,
                             java.util.Map<String, java.util.List<String>> edges,
                             JsonObject nodes, int[] paths) {
        JsonObject def = nodes.getAsJsonObject(node);
        check(def != null, "edge names a real node: " + node);
        check(def.get("layer").getAsInt() == depth, node + " sits on layer " + depth);
        if (def.has("final") && def.get("final").getAsBoolean()) {
            check(depth == 3, "every path is exactly three floors, this one ends at " + depth);
            paths[0]++;
            return;
        }
        for (String next : edges.getOrDefault(node, java.util.List.of())) {
            walk(next, depth + 1, layers, edges, nodes, paths);
        }
    }

    /** No borrowed rooms in a Cow Pits roomBias: every biased room is a Cow Pits room. */
    private static void testNoBorrowedRooms() throws Exception {
        Path rooms = root().resolve("data/pocketdungeons/dungeon_room");
        JsonObject dungeon = read(root().resolve("data/pocketdungeons/dungeon/cow_pits.json"));
        for (JsonElement el : dungeon.getAsJsonArray("nodes")) {
            JsonObject node = el.getAsJsonObject();
            if (!node.has("roomBias")) {
                continue;
            }
            for (JsonElement biased : node.getAsJsonArray("roomBias")) {
                JsonObject room = read(rooms.resolve(biased.getAsString() + ".json"));
                boolean own = false;
                for (JsonElement d : room.getAsJsonArray("dungeons")) {
                    own |= d.getAsString().equals("cow_pits");
                }
                check(own, node.get("id").getAsString() + " biases a foreign room: " + biased.getAsString());
            }
        }
    }

    private static void scan(JsonElement node, String where) {
        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
                String name = obj.get("name").getAsString();
                check(!FORBIDDEN.contains(name) && !name.endsWith("_seeds"), where + " holds " + name);
            }
            obj.entrySet().forEach(e -> scan(e.getValue(), where));
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(e -> scan(e, where));
        }
    }

    private static JsonObject read(Path file) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return JsonParser.parseString(text.startsWith("﻿") ? text.substring(1) : text).getAsJsonObject();
    }

    private static Path root() throws Exception {
        java.net.URL url = CowPitsTest.class.getClassLoader().getResource("data/pocketdungeons/dungeon_theme");
        return Paths.get(url.toURI()).getParent().getParent().getParent();
    }

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }
}
