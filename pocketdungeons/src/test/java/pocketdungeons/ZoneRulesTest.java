package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Headless regression for the zone rules hook ({@link ZoneRules}): the
 * default zone's numbers, the arithmetic every rule feeds (depth bonus chests,
 * the loot tier, the capstone), the
 * {@code rules} block's parsing and its refusals, the Endless Mine expressed
 * through its own theme file, and the unlock level filtering the door pick
 * without disturbing it.
 */
public class ZoneRulesTest {

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testDefaultZone();
        testDepthBonus();
        testLootTier();
        testCapstone();
        testParsing();
        testRefusals();
        testMineThemeFile();
        testUnlockFilterKeepsThePick();

        System.out.println("ZoneRulesTest passed");
    }

    /** The ordinary dungeon: standard floors, no capstone of its own, the documented numbers. */
    private static void testDefaultZone() {
        ZoneRules d = ZoneRules.DEFAULT;
        check(d.floorKindAt(1).equals("standard") && d.floorKindAt(7).equals("standard"), "standard floors");
        check(d.capstone() == null, "the default leaves the capstone to the adventure node");
        check(d.lootRole().equals("gear"), "the gear faucet");
        check(d.lootTierEvery() == 0, "no depth tier");
        check(d.unlockLevel() == 1, "offered from level 1");
        check(d.kitTopUpScale() == 1.0, "kit top-up unscaled");
        check(ZoneRules.forTheme(null) == ZoneRules.DEFAULT, "no theme is the default zone");
        check(ZoneRules.forTheme("nobody:unknown") == ZoneRules.DEFAULT, "an unknown theme is the default zone");
    }

    /** A third of a chest per floor from the second: nothing on 1 to 3, then one, two, three, capped. */
    private static void testDepthBonus() {
        ZoneRules d = ZoneRules.DEFAULT;
        int[] expected = {0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3, 3};
        for (int floors = 0; floors < expected.length; floors++) {
            checkEquals(d.bonusChests(floors), expected[floors], "default bonus at " + floors + " floors");
        }
        ZoneRules written = parse("{\"depth_bonus\": 0.3333}");
        checkEquals(written.bonusChests(4), 1, "a third written as 0.3333 still pays on floor 4");
        ZoneRules none = parse("{\"depth_bonus\": 0}");
        checkEquals(none.bonusChests(10), 0, "no depth bonus");
        ZoneRules steep = parse("{\"depth_bonus\": 1}");
        checkEquals(steep.bonusChests(2), 1, "a chest a floor from floor 2");
        checkEquals(steep.bonusChests(9), ZoneRules.MAX_BONUS_CHESTS, "capped");
    }

    private static void testLootTier() {
        checkEquals(ZoneRules.DEFAULT.lootTier(1, 9), 1, "the default zone keeps the key's tier");
        ZoneRules every3 = parse("{\"loot_tier_every\": 3}");
        checkEquals(every3.lootTier(1, 0), 1, "floor 0 keeps the base tier");
        checkEquals(every3.lootTier(1, 2), 1, "floor 2 keeps the base tier");
        checkEquals(every3.lootTier(1, 3), 2, "floor 3 escalates");
        checkEquals(every3.lootTier(1, 6), 3, "floor 6 escalates again");
        checkEquals(every3.lootTier(1, 12), 3, "capped at the top tier");
        checkEquals(every3.lootTier(3, 3), 3, "the top tier stays the top tier");
    }

    private static void testCapstone() {
        check(ZoneRules.capstoneFor(ZoneRules.DEFAULT, true) == ZoneRules.Capstone.BOSS,
                "a boss node without a rule is a boss floor, as before");
        check(ZoneRules.capstoneFor(ZoneRules.DEFAULT, false) == ZoneRules.Capstone.NONE, "an ordinary node");
        check(ZoneRules.capstoneFor(parse("{\"capstone\": \"none\"}"), true) == ZoneRules.Capstone.NONE,
                "an explicit none wins");
        check(ZoneRules.capstoneFor(parse("{\"capstone\": \"boss\"}"), false) == ZoneRules.Capstone.BOSS,
                "an explicit boss wins");
    }

    private static void testParsing() {
        ZoneRules full = parse("""
                {"floor_sequence": ["standard", "standard"], "capstone": "boss", "depth_bonus": 0.5,
                 "loot_role": "trophy",
                 "loot_tier_every": 2, "unlock_level": 25, "kit_top_up_scale": 0.5}
                """);
        check(full.floorSequence().equals(List.of("standard", "standard")), "floor sequence");
        check(full.capstone() == ZoneRules.Capstone.BOSS, "capstone");
        check(full.depthBonus() == 0.5, "numbers");
        check(full.lootRole().equals("trophy") && full.lootTierEvery() == 2, "loot");
        check(full.unlockLevel() == 25 && full.kitTopUpScale() == 0.5, "unlock and kit");
        ZoneRules empty = parse("{}");
        check(empty.equals(ZoneRules.DEFAULT), "an empty block is the default zone");

        DungeonThemeMeta plain = DungeonThemeMeta.fromJson(JsonParser.parseString(
                "{\"name\":\"Plain\",\"processors\":\"pocketdungeons:theme_deepslate\"}").getAsJsonObject());
        check(plain.rules == null, "a theme without a rules block has none");
        DungeonThemeMeta ruled = DungeonThemeMeta.fromJson(JsonParser.parseString(
                "{\"name\":\"Ruled\",\"processors\":\"pocketdungeons:theme_deepslate\","
                        + "\"rules\":{\"unlock_level\":10}}").getAsJsonObject());
        check(ruled.rules != null && ruled.rules.unlockLevel() == 10, "a theme's rules block is read");
    }

    private static void testRefusals() {
        expectFailure("{\"floor_kind\": \"shaft\"}", "not built yet");
        expectFailure("{\"floor_sequence\": [\"standard\", \"drift\"]}", "not built yet");
        expectFailure("{\"floor_sequence\": []}", "must not be empty");
        expectFailure("{\"capstone\": \"scenario_end\"}", "capstone");
        expectFailure("{\"depth_bonus\": -1}", "depth_bonus");
        expectFailure("{\"loot_role\": \"gold\"}", "loot_role");
        expectFailure("{\"unlock_level\": 0}", "unlock_level");
        // J3: the omen knobs are gone, and a rules block naming one is refused like any typo.
        expectFailure("{\"omen_scale\": 9}", "unknown field");
        expectFailure("{\"omen_base\": {\"amount\": 5}}", "unknown field");
        expectFailure("{\"depth_bonuss\": 1}", "unknown field");
    }

    /** The Endless Mine's current behaviour, read from its own theme file through the hook. */
    private static void testMineThemeFile() throws Exception {
        JsonObject file;
        try (InputStreamReader reader = new InputStreamReader(ZoneRulesTest.class.getResourceAsStream(
                "/data/pocketdungeons/dungeon_theme/endless_mine.json"), StandardCharsets.UTF_8)) {
            file = JsonParser.parseReader(reader).getAsJsonObject();
        }
        ZoneRules mine = DungeonThemeMeta.fromJson(file, EndlessMineRules.MINE_THEME_ID).rules;
        check(mine != null, "the Mine theme carries a rules block");
        checkEquals(mine.lootTier(1, 0), 1, "Mine floor 0 keeps base tier");
        checkEquals(mine.lootTier(1, 3), 2, "Mine floor 3 escalates to tier 2");
        checkEquals(mine.lootTier(1, 6), 3, "Mine floor 6 escalates to tier 3");
        checkEquals(mine.lootTier(1, 12), 3, "Mine tier caps at 3");
        checkEquals(mine.lootTier(3, 3), 3, "Mine at base tier 3 stays 3");
        check(mine.lootRole().equals("materials"), "the Mine is the materials faucet");
        check(mine.unlockLevel() == 1, "the Mine is dealt at any level, as today");
        checkEquals(mine.bonusChests(4), ZoneRules.DEFAULT.bonusChests(4), "the Mine's depth bonus is the default");
    }

    /**
     * With every theme offerable the filtered pick is the plain pick; a filter
     * that refuses everything falls back to the plain pick too.
     */
    private static void testUnlockFilterKeepsThePick() {
        AdventureGraph graph = AdventureGraphs.current().graph();
        UUID owner = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        List<String> plain = graph.pick(owner, "", 0);
        check(plain.equals(graph.pick(owner, "", 0, theme -> true)), "an open filter keeps the pick");
        check(plain.equals(graph.pick(owner, "", 0, theme -> false)), "a closed filter falls back to the pick");
    }

    private static ZoneRules parse(String json) {
        return ZoneRules.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static void expectFailure(String json, String message) {
        try {
            parse(json);
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(message)) {
                throw new AssertionError("wrong failure for " + json + ": " + expected.getMessage());
            }
            return;
        }
        throw new AssertionError("expected " + json + " to be refused (" + message + ")");
    }

    private static void checkEquals(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
