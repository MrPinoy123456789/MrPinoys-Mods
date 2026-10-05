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
        System.out.println("CowPitsTest: all checks passed");
    }

    private static void testCap() {
        check(CowPits.FLOOR_MIN == 6 && CowPits.FLOOR_CAP == 10, "6 to 10 cows per floor");
        check(CowPits.maxPossible() <= CowPits.FLOOR_CAP, "the room set never exceeds the cap: " + CowPits.maxPossible());
        check(CowPits.maxPossible() >= CowPits.FLOOR_MIN, "and can reach the minimum");
        check(CowPits.ROOM_COWS.get("cow_pens") * CowPits.ROOM_LIMITS.get("cow_pens") >= CowPits.FLOOR_MIN,
                "the pens alone reach the minimum");
        check(CowPits.spawnCount(3, 0) == 3, "a room spawns its own count on an empty floor");
        check(CowPits.spawnCount(3, 8) == 2, "the cap trims the last room");
        check(CowPits.spawnCount(3, 10) == 0, "a full floor spawns none");
        check(CowPits.spawnCount(3, 14) == 0, "an over full floor spawns none");
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
