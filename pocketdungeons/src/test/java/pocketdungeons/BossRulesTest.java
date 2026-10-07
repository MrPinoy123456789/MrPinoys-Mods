package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pure-JDK regression for dungeon structure W7b: the Wither's rules ({@link WitherRules}), Herobrine's
 * phases and the rescue trigger ({@link HerobrineRules}), the capstone start omen ({@link CapstoneStart}),
 * and the shipped W7b data (boss rooms, the W6 legacy theme lists, diary pages, no dashes in what a
 * player reads).
 */
public class BossRulesTest {

    private static final String T = "pocketdungeons:";
    private static final Path DATA = Path.of("src/main/resources/data/pocketdungeons");

    private static final String SHAPE = """
            {"name": "NAME", "act": 4, "kind": "KIND", "mainTheme": "frostworks",
             "lootBand": {"min": 3, "max": 4},
             "nodes": [
               {"id": "gate", "name": "Gate", "layer": 1},
               {"id": "mid", "name": "Mid", "layer": 2},
               {"id": "end", "name": "End", "layer": 3, "final": true}
             ],
             "edges": [
               {"from": "gate", "to": "mid"},
               {"from": "mid", "to": "end"}
             ]}
            """;

    private static DungeonDef def(String id, String kind) {
        return DungeonDef.fromJson(T + id, JsonParser.parseString(SHAPE.replace("NAME", id).replace("KIND", kind))
                .getAsJsonObject());
    }

    public static void main(String[] args) throws IOException {
        testWitherHealth();
        testWitherContainment();
        testWitherSummonAndPad();
        testHerobrinePhases();
        testHerobrineScaling();
        testRescueTrigger();
        testAdds();
        testHerobrinePadAndStart();
        testCapstoneStart();
        testShippedRooms();
        testShippedLegacyThemes();
        testShippedDiaries();
        testNoDashes();
        System.out.println("BossRulesTest passed");
    }

    // ---- the Wither ----------------------------------------------------------------------

    private static void testWitherHealth() {
        check(WitherRules.maxHealth(1) == WitherRules.BASE_HEALTH, "a solo Wither has the base health");
        check(WitherRules.maxHealth(0) == WitherRules.maxHealth(1), "an empty party counts as one");
        check(WitherRules.maxHealth(2) == WitherRules.BASE_HEALTH + WitherRules.HEALTH_PER_EXTRA_MEMBER,
                "each extra member adds health");
        double last = 0;
        for (int party = 1; party <= 8; party++) {
            double health = WitherRules.maxHealth(party);
            check(health >= last, "health never falls as the party grows: " + party);
            check(health <= WitherRules.MAX_HEALTH, "health is capped: " + health);
            last = health;
        }
        check(WitherRules.maxHealth(100) == WitherRules.MAX_HEALTH, "a huge party hits the cap");
    }

    private static void testWitherContainment() {
        check(!WitherRules.outsideRoom(12.5, 1.0, 8.5), "the spawn point is inside the room");
        check(!WitherRules.outsideRoom(8.0, 2.0, 8.0), "the middle of the room is inside");
        check(WitherRules.outsideRoom(0.5, 1.0, 8.0), "the west doorway is outside");
        check(WitherRules.outsideRoom(-3.0, 1.0, 8.0), "the corridor beyond the door is outside");
        check(WitherRules.outsideRoom(15.5, 1.0, 8.0), "the east wall is outside");
        check(WitherRules.outsideRoom(8.0, 1.0, 0.2) && WitherRules.outsideRoom(8.0, 1.0, 15.8),
                "the north and south walls are outside");
        check(WitherRules.outsideRoom(8.0, 3.5, 8.0), "up in the ceiling is outside");
        check(WitherRules.outsideRoom(8.0, 0.0, 8.0), "down in the floor is outside");
        check(!WitherRules.outsideRoom(WitherRules.INSET, 1.0, WitherRules.INSET), "the inset line itself is inside");
        check(WitherRules.outsideRoom(WitherRules.INSET - 0.01, 1.0, 8.0), "just past the inset line is outside");
    }

    private static void testWitherSummonAndPad() {
        check(WitherRules.shouldSummon(true, false, 1), "a member in the room on the final floor wakes it");
        check(!WitherRules.shouldSummon(true, false, 0), "nobody in the room, nothing wakes");
        check(!WitherRules.shouldSummon(true, true, 3), "it is summoned once");
        check(!WitherRules.shouldSummon(false, false, 3), "never off the final floor");
        check(WitherRules.padRefusal(false, false, false) != null, "the pad is shut before the summon");
        check(WitherRules.padRefusal(true, false, true) != null, "the pad is shut while the Wither stands");
        check(WitherRules.padRefusal(true, false, false) == null, "the pad opens when the Wither is dead");
        check(WitherRules.padRefusal(false, true, false) == null, "a failed spawn never locks the floor");
    }

    // ---- Herobrine ------------------------------------------------------------------------

    private static void testHerobrinePhases() {
        double max = 300;
        check(HerobrineRules.phase(300, max) == HerobrineRules.Phase.MELEE, "full health is phase one");
        check(HerobrineRules.phase(201, max) == HerobrineRules.Phase.MELEE, "just above two thirds is phase one");
        check(HerobrineRules.phase(200, max) == HerobrineRules.Phase.SUMMONS, "two thirds is phase two");
        check(HerobrineRules.phase(101, max) == HerobrineRules.Phase.SUMMONS, "just above one third is phase two");
        check(HerobrineRules.phase(100, max) == HerobrineRules.Phase.BLINK, "one third is phase three");
        check(HerobrineRules.phase(1, max) == HerobrineRules.Phase.BLINK, "low health is phase three");
        check(HerobrineRules.phase(5, 0) == HerobrineRules.Phase.MELEE, "a bad max health reads as phase one");
        int last = 0;
        for (int h = 300; h >= 0; h -= 10) {
            int number = HerobrineRules.phase(h, max).number;
            check(number >= last, "phases only advance as health falls: " + h);
            last = number;
        }
    }

    private static void testHerobrineScaling() {
        check(HerobrineRules.maxHealth(1) == HerobrineRules.BASE_HEALTH, "a solo Steve has the base health");
        check(HerobrineRules.maxHealth(3) == HerobrineRules.BASE_HEALTH + 2 * HerobrineRules.HEALTH_PER_EXTRA_MEMBER,
                "two extra members add two portions of health");
        check(HerobrineRules.maxHealth(50) == HerobrineRules.MAX_HEALTH, "health is capped");
        check(HerobrineRules.attackDamage(1) == HerobrineRules.BASE_DAMAGE, "a solo Steve has the base damage");
        check(HerobrineRules.attackDamage(50) == HerobrineRules.MAX_DAMAGE, "damage is capped");
        check(HerobrineRules.attackDamage(4) > HerobrineRules.attackDamage(2), "damage grows with the party");
    }

    private static void testRescueTrigger() {
        check(HerobrineRules.playerLow(5, 20), "5 of 20 is low");
        check(HerobrineRules.playerLow(4, 20), "4 of 20 is low");
        check(!HerobrineRules.playerLow(6, 20), "6 of 20 is not low");
        check(HerobrineRules.bossLow(40, 400), "a tenth is low for Steve");
        check(!HerobrineRules.bossLow(41, 400), "just over a tenth is not low");
        check(HerobrineRules.shouldRescue(false, true, false), "a low member fires the rescue");
        check(HerobrineRules.shouldRescue(false, false, true), "a low Steve fires the rescue");
        check(HerobrineRules.shouldRescue(false, true, true), "both fire it once");
        check(!HerobrineRules.shouldRescue(false, false, false), "nothing low, nothing fires");
        check(!HerobrineRules.shouldRescue(true, true, true), "it never fires twice");
        // Whichever comes first: a member falls while Steve is healthy, or Steve falls while the party is healthy.
        check(HerobrineRules.shouldRescue(false, HerobrineRules.playerLow(3, 20), HerobrineRules.bossLow(390, 400)),
                "the member fell first");
        check(HerobrineRules.shouldRescue(false, HerobrineRules.playerLow(20, 20), HerobrineRules.bossLow(30, 400)),
                "Steve fell first");
    }

    private static void testAdds() {
        check(HerobrineRules.addsToSpawn(HerobrineRules.Phase.MELEE, 4, 0) == 0, "no adds in phase one");
        check(HerobrineRules.addsToSpawn(HerobrineRules.Phase.SUMMONS, 1, 0) == 2, "a solo phase two wave is two");
        check(HerobrineRules.addsToSpawn(HerobrineRules.Phase.BLINK, 1, 0) == 3, "a solo phase three wave is three");
        check(HerobrineRules.addsToSpawn(HerobrineRules.Phase.BLINK, 3, 0) == 5, "a party of three gets five");
        check(HerobrineRules.addsToSpawn(HerobrineRules.Phase.BLINK, 8, 0) == HerobrineRules.MAX_ADDS,
                "a wave never exceeds the cap");
        check(HerobrineRules.addsToSpawn(HerobrineRules.Phase.SUMMONS, 1, HerobrineRules.MAX_ADDS) == 0,
                "no more adds when the cap is standing");
        check(HerobrineRules.addsToSpawn(HerobrineRules.Phase.SUMMONS, 4, HerobrineRules.MAX_ADDS - 1) == 1,
                "only the room under the cap is filled");
    }

    private static void testHerobrinePadAndStart() {
        check(HerobrineRules.padRefusal(false) != null, "the pad is shut before the rescue is done");
        check(HerobrineRules.padRefusal(true) == null, "the pad opens when the rescue is done");
        check(HerobrineRules.shouldStart(true, false, 1), "a member in the room starts the fight");
        check(!HerobrineRules.shouldStart(true, true, 1), "it starts once");
        check(!HerobrineRules.shouldStart(true, false, 0), "nobody in the room, no fight");
        check(!HerobrineRules.shouldStart(false, false, 2), "never off the final floor");
    }

    // ---- the capstone start omen ---------------------------------------------------------------

    private static void testCapstoneStart() {
        DungeonDef capstone = def("c", "capstone");
        DungeonDef story = def("s", "dungeon");
        check(CapstoneStart.amount(capstone, "end", 1) == 1, "a capstone's final floor starts with 1 omen");
        check(CapstoneStart.amount(capstone, "mid", 1) == 0, "a capstone's other floors start clean");
        check(CapstoneStart.amount(capstone, "gate", 1) == 0, "the entry floor starts clean");
        check(CapstoneStart.amount(story, "end", 1) == 0, "a story dungeon's final floor starts clean");
        check(CapstoneStart.amount(null, "end", 1) == 0, "no dungeon, no head start");
        check(CapstoneStart.amount(capstone, "end", 0) == 0, "zero in the config turns it off");
        check(CapstoneStart.amount(capstone, "end", 9) == Omen.MAX_OMEN, "the config value is clamped to the omen cap");
        check(CapstoneStart.amount(capstone, "end", -2) == 0, "a negative config value is zero");
        check(CapstoneStart.raise(0, 1) == 1, "the head start raises omen from 0 to 1");
        check(CapstoneStart.raise(3, 1) == 3, "it never lowers omen");
        check(CapstoneStart.raise(1, 1) == 1, "it does not stack on equal omen");
    }

    // ---- shipped data ------------------------------------------------------------------------

    private static JsonObject read(String relative) throws IOException {
        String text = Files.readString(DATA.resolve(relative), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static List<String> strings(JsonObject obj, String key) {
        List<String> out = new java.util.ArrayList<>();
        if (obj.has(key)) {
            obj.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    private static void testShippedRooms() throws IOException {
        for (String boss : List.of("wither_hall", "fracture_hall")) {
            JsonObject room = read("dungeon_room/" + boss + ".json");
            check(strings(room, "roles").equals(List.of("exit")), boss + " is an exit room");
            check(strings(room, "graphRole").equals(List.of("capstone")), boss + " is a capstone room");
            check(Files.exists(DATA.resolve("structure/rooms/" + boss + ".nbt")), boss + " has a template");
        }
        check(strings(read("dungeon_room/wither_hall.json"), "dungeons").equals(List.of("wither_keep")),
                "wither_hall belongs to wither_keep only");
        check(strings(read("dungeon_room/fracture_hall.json"), "dungeons").equals(List.of("herobrine")),
                "fracture_hall belongs to herobrine only");
        for (String name : List.of("warped_forest", "crimson_forest", "soul_sand_valley", "end_island",
                "end_city_hall", "end_ship")) {
            JsonObject room = read("dungeon_room/" + name + ".json");
            check(!strings(room, "dungeons").isEmpty() && strings(room, "dungeons").equals(strings(room, "theme")),
                    name + " lists the same dungeons in dungeons and theme");
            check(!strings(room, "acts").isEmpty(), name + " names its act");
            check(Files.exists(DATA.resolve("structure/rooms/" + name + ".nbt")), name + " has a template");
        }
        JsonObject warped = read("dungeon_room/warped_forest.json");
        check("warped_forest".equals(warped.get("biome").getAsString()), "warped_forest biome");
        check(strings(warped, "dungeons").containsAll(List.of("basalt_foundry", "blackstone", "wither_keep")),
                "the warped forest serves every act 4 dungeon");
        check(warped.getAsJsonArray("nodes").toString().contains("warped_stem")
                && warped.getAsJsonArray("nodes").toString().contains("warped_wart_block"),
                "the warped forest declares stem and wart block nodes");
        check(read("dungeon_room/crimson_forest.json").getAsJsonArray("nodes").toString().contains("crimson_stem"),
                "the crimson forest declares stem nodes");
    }

    /** The W6 rooms carry a legacy theme list beside dungeons, so the legacy filter cannot draw them for any theme. */
    private static void testShippedLegacyThemes() throws IOException {
        for (String name : List.of("mineshaft_tunnel", "mineshaft_crossing", "mineshaft_seam", "mineshaft_collapse",
                "lush_hollow", "lush_root_gallery", "lush_clay_pool", "cow_pens", "hay_loft", "cow_yard",
                "burrow_tunnel", "ossuary_passage")) {
            JsonObject room = read("dungeon_room/" + name + ".json");
            List<String> dungeons = strings(room, "dungeons");
            List<String> theme = strings(room, "theme");
            check(!dungeons.isEmpty(), name + " names its dungeon");
            check(!theme.isEmpty(), name + " has a legacy theme list");
            check(theme.containsAll(dungeons), name + " theme list holds its dungeons: " + theme);
            if (name.startsWith("mineshaft_")) {
                check(theme.contains("deepslate"), name + " also names the Endless Mine's room theme");
            } else {
                check(theme.equals(dungeons), name + " theme list is its dungeon alone: " + theme);
            }
        }
    }

    private static void testShippedDiaries() throws IOException {
        java.util.Set<Integer> numbers = new java.util.HashSet<>();
        java.util.Set<Integer> bands = new java.util.HashSet<>();
        try (Stream<Path> files = Files.list(DATA.resolve("diary"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                JsonObject entry = read("diary/" + file.getFileName());
                check(numbers.add(entry.get("number").getAsInt()), "diary number is unique: " + file);
                check(bands.add(entry.get("band").getAsInt()), "diary band is unique: " + file);
            }
        }
        try (Stream<Path> files = Files.list(DATA.resolve("dungeon"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                JsonObject dungeon = read("dungeon/" + file.getFileName());
                if ("endless".equals(dungeon.get("kind").getAsString())) {
                    continue;
                }
                String diary = dungeon.has("diary") ? dungeon.get("diary").getAsString() : "";
                check(!diary.isEmpty() && Files.exists(DATA.resolve("diary/" + diary + ".json")),
                        file.getFileName() + " wires an existing diary page");
            }
        }
        check(read("dungeon/herobrine.json").get("diary").getAsString().equals("entry_25"), "herobrine owns entry_25");
        JsonObject alex = read("diary/entry_25.json");
        check(alex.get("pages").toString().contains("The search continues"),
                "Alex's last page says the search continues");
    }

    /** House rule: no em dash and no double hyphen as punctuation in anything a player reads. */
    private static void testNoDashes() throws IOException {
        List<Path> files = new java.util.ArrayList<>();
        for (String dir : List.of("diary", "dungeon", "dungeon_room", "dungeon_theme", "dungeon_adventure")) {
            try (Stream<Path> stream = Files.list(DATA.resolve(dir))) {
                stream.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            }
        }
        for (String source : List.of("HerobrineFight", "WitherFight", "HerobrineRules", "WitherRules", "CapstoneStart",
                "NetherEndSpecs")) {
            files.add(Path.of("src/main/java/pocketdungeons/" + source + ".java"));
        }
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            check(!text.contains("—"), file + " has an em dash");
            check(!text.contains(" -- "), file + " has a double hyphen");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
