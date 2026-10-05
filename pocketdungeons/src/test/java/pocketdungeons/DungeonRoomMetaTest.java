package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Regression test for {@link DungeonRoomMeta}'s parser, and specifically for the
 * {@code processors} field that M1 wired.
 *
 * <p>{@code DungeonRoomMeta} is a plain data holder with no Minecraft imports, so
 * the whole parser is reachable from the pure-Java harness. The half that needs a
 * registry -- resolving the id to a {@code StructureProcessorList} -- lives in
 * {@link TemplateStamper} and {@code RoomManifest} and is verified against a
 * running server instead.
 */
public class DungeonRoomMetaTest {

    public static void main(String[] args) {
        testProcessorsAbsent();
        testProcessorsPresent();
        testProcessorsBlank();
        testUnrelatedFieldsStillParse();
        testThemeAbsent();
        testThemePresent();
        testThemeEmptyArray();
        testSituationFieldDefaults();
        testTierRoundTrip();
        testProvidesRoundTrip();
        testRequiresRoundTrip();
        testPressureRoundTrip();
        testAccessRoundTrip();
        testAccessRejectsUnknownValue();
        testWindowRoundTrip();
        testWindowRejectsUnknownValue();
        testW4Defaults();
        testW4FieldsRoundTrip();
        testW4NodeForms();
        testW4NodeRejections();
        testW4ChoiceRejections();
        testW4ThemeStandsInForDungeons();
        System.out.println("DungeonRoomMetaTest passed");
    }

    /** Every room shipped today omits the field; it must stay optional. */
    private static void testProcessorsAbsent() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"]
                }
                """);
        check(meta.processors, null, "absent processors");
    }

    private static void testProcessorsPresent() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "processors": "minecraft:mossify_10_percent"
                }
                """);
        check(meta.processors, "minecraft:mossify_10_percent", "present processors");
    }

    /**
     * Blank must read as absent, not as the empty identifier. Left as {@code ""}
     * it reaches {@code Identifier.parse("")}, which throws, and the room is
     * rejected for what looks like a template problem.
     */
    private static void testProcessorsBlank() {
        check(parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "processors": ""
                }
                """).processors, null, "empty processors");
        check(parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "processors": "   "
                }
                """).processors, null, "whitespace processors");
    }

    private static void testThemeAbsent() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"]
                }
                """);
        check(meta.theme.isEmpty(), true, "absent theme is empty");
    }

    private static void testThemePresent() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "theme": ["deepslate", "prismarine"]
                }
                """);
        check(meta.theme.size(), 2, "theme size");
        check(meta.theme.get(0), "deepslate", "theme[0]");
        check(meta.theme.get(1), "prismarine", "theme[1]");
    }

    private static void testThemeEmptyArray() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "theme": []
                }
                """);
        check(meta.theme.isEmpty(), true, "empty theme array");
    }

    /** The field is additive: nothing else about the schema moved. */
    private static void testUnrelatedFieldsStillParse() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_tee",
                  "footprint": [1, 1],
                  "roles": ["encounter", "loot"],
                  "weight": 4,
                  "minDepth": 2,
                  "maxPerDungeon": 3,
                  "processors": "pocketdungeons:theme_deepslate"
                }
                """);
        check(meta.template, "pocketdungeons:rooms/hall_tee", "template");
        check(meta.footprintX, 1, "footprintX");
        check(meta.footprintZ, 1, "footprintZ");
        check(meta.roles.size(), 2, "roles size");
        check(meta.weight, 4, "weight");
        check(meta.minDepth, 2, "minDepth");
        check(meta.maxPerDungeon, 3, "maxPerDungeon");
        check(meta.processors, "pocketdungeons:theme_deepslate", "processors");
    }

    // ---- M45: the spec 6.1 situation fields ---------------------------------

    /** Every room shipped today omits all five; the defaults are what they get. */
    private static void testSituationFieldDefaults() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"]
                }
                """);
        check(meta.tier, 1, "default tier");
        check(meta.provides.isEmpty(), true, "default provides is empty");
        check(meta.requires.isEmpty(), true, "default requires is empty");
        check(meta.pressure, null, "default pressure");
        check(meta.access, "open", "default access");
        check(meta.window, "bars", "default window");
    }

    private static void testTierRoundTrip() {
        check(parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "tier": 3
                }
                """).tier, 3, "tier");
    }

    private static void testProvidesRoundTrip() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "provides": ["water", "blocks"]
                }
                """);
        check(meta.provides.size(), 2, "provides size");
        check(meta.provides.get(0), "water", "provides[0]");
        check(meta.provides.get(1), "blocks", "provides[1]");
    }

    private static void testRequiresRoundTrip() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "requires": ["lead"]
                }
                """);
        check(meta.requires.size(), 1, "requires size");
        check(meta.requires.get(0), "lead", "requires[0]");
    }

    private static void testPressureRoundTrip() {
        check(parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "pressure": "omen"
                }
                """).pressure, "omen", "pressure");
    }

    private static void testAccessRoundTrip() {
        check(parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "access": "gated"
                }
                """).access, "gated", "gated access");
        check(parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "access": "open"
                }
                """).access, "open", "explicit open access");
    }

    /**
     * A third value is a typo, and reading it as {@code open} would quietly
     * un-gate a room the author meant to gate.
     */
    private static void testAccessRejectsUnknownValue() {
        boolean threw = false;
        try {
            parse("""
                    {
                      "template": "pocketdungeons:rooms/hall_straight",
                      "roles": ["corridor"],
                      "access": "locked"
                    }
                    """);
        } catch (IllegalArgumentException e) {
            threw = true;
            if (!e.getMessage().contains("hall_straight") || !e.getMessage().contains("locked")) {
                throw new AssertionError(
                        "access rejection must name the room and the bad value: " + e.getMessage());
            }
        }
        check(threw, true, "bad access value throws");
    }

    private static void testWindowRoundTrip() {
        check(parseWindow("glass"), "glass", "glass window");
        check(parseWindow("tinted_glass"), "tinted_glass", "tinted glass window");
        check(parseWindow("none"), "none", "no window");
        check(parseWindow("bars"), "bars", "explicit bars window");
    }

    /** A misspelt material that fell back to bars would blind a room on purpose. */
    private static void testWindowRejectsUnknownValue() {
        boolean threw = false;
        try {
            parseWindow("stained_glass");
        } catch (IllegalArgumentException e) {
            threw = true;
            if (!e.getMessage().contains("hall_straight")
                    || !e.getMessage().contains("stained_glass")) {
                throw new AssertionError(
                        "window rejection must name the room and the bad value: " + e.getMessage());
            }
        }
        check(threw, true, "bad window value throws");
    }

    // ---- dungeon structure W4 --------------------------------------------------

    /** Every room shipped before W4 omits the new fields; each must read as the old behaviour. */
    private static void testW4Defaults() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"]
                }
                """);
        check(meta.nodes.isEmpty(), true, "no nodes");
        check(meta.light, "lit", "light defaults lit");
        check(meta.corridor, "wide", "corridor defaults wide");
        check(meta.biome, null, "no biome");
        check(meta.requiresLight, false, "requiresLight defaults false");
        check(meta.dungeons.isEmpty(), true, "no dungeons");
        check(meta.acts.isEmpty(), true, "no acts");
        check(meta.graphRole.isEmpty(), true, "no graph role");
        check(meta.borrowableBy.isEmpty(), true, "no borrowers");
        // The legacy constructors read the same.
        DungeonRoomMeta legacy = new DungeonRoomMeta("t", 1, 1, java.util.List.of(RoleIds.CORRIDOR), 1, 0, -1, null);
        check(legacy.light, "lit", "legacy light");
        check(legacy.corridor, "wide", "legacy corridor");
        check(legacy.nodes.isEmpty(), true, "legacy nodes");
    }

    private static void testW4FieldsRoundTrip() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/mine_seam",
                  "roles": ["corridor"],
                  "light": "dim",
                  "corridor": "narrow",
                  "biome": "mineshaft",
                  "requiresLight": true,
                  "dungeons": ["mineshaft", "pocketdungeons:rootworks"],
                  "acts": [1, 2],
                  "graphRole": ["entry", "side_reward"],
                  "borrowableBy": ["infestation"]
                }
                """);
        check(meta.light, "dim", "light");
        check(meta.corridor, "narrow", "corridor");
        check(meta.biome, "mineshaft", "biome");
        check(meta.requiresLight, true, "requiresLight");
        check(meta.dungeons, java.util.List.of("mineshaft", "pocketdungeons:rootworks"), "dungeons");
        check(meta.acts, java.util.List.of(1, 2), "acts");
        check(meta.graphRole, java.util.List.of("entry", "side_reward"), "graphRole");
        check(meta.borrowableBy, java.util.List.of("infestation"), "borrowableBy");
        check(meta.allowsAct(2), true, "act 2 allowed");
        check(meta.allowsAct(3), false, "act 3 not allowed");
        check(parse("{\"template\": \"t\", \"roles\": [\"corridor\"]}").allowsAct(5), true, "no acts allows any");
        // light dark is parsed as written; the validator reports the dark plus requiresLight pair.
        check(parse("{\"template\": \"t\", \"roles\": [\"corridor\"], \"light\": \"dark\", \"requiresLight\": true}")
                .light, "dark", "dark parses");
    }

    private static void testW4NodeForms() {
        DungeonRoomMeta meta = parse("""
                {
                  "template": "pocketdungeons:rooms/seam",
                  "roles": ["corridor"],
                  "nodes": [
                    {"block": "minecraft:iron_ore", "at": [3, 1, 4]},
                    {"block": "coal_ore", "from": [5, 1, 5], "to": [7, 2, 6]},
                    {"block": "minecraft:oak_log", "from": [1, 1, 1], "to": [4, 1, 4], "count": 3}
                  ]
                }
                """);
        check(meta.nodes.size(), 3, "three nodes");
        DungeonRoomMeta.NodeSpec single = meta.nodes.get(0);
        check(single.block(), "minecraft:iron_ore", "block kept");
        check(single.positions(0L).size(), 1, "a single position");
        check(java.util.Arrays.equals(single.positions(0L).get(0), new int[]{3, 1, 4}), true, "at position");
        check(meta.nodes.get(1).block(), "minecraft:coal_ore", "bare block id is qualified");
        check(meta.nodes.get(1).positions(0L).size(), 12, "a 3 by 2 by 2 box has 12 positions");
        DungeonRoomMeta.NodeSpec counted = meta.nodes.get(2);
        check(counted.volume(), 16, "volume of a 4 by 1 by 4 box");
        check(counted.positions(7L).size(), 3, "count picks three");
        // Deterministic: the same seed picks the same three, a different seed may not.
        check(java.util.Arrays.deepEquals(counted.positions(7L).toArray(), counted.positions(7L).toArray()), true,
                "same seed, same nodes");
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (int[] p : counted.positions(7L)) {
            distinct.add(p[0] + "," + p[1] + "," + p[2]);
            check(p[0] >= 1 && p[0] <= 4 && p[2] >= 1 && p[2] <= 4 && p[1] == 1, true, "inside the box");
        }
        check(distinct.size(), 3, "three distinct positions");
        // A count larger than the box is the whole box.
        check(parse("""
                {"template": "t", "roles": ["corridor"],
                 "nodes": [{"block": "stone", "from": [1, 1, 1], "to": [2, 1, 1], "count": 9}]}
                """).nodes.get(0).positions(1L).size(), 2, "count above the box is the box");
    }

    private static void testW4NodeRejections() {
        for (String bad : new String[]{
                "{\"block\": \"stone\"}",
                "{\"block\": \"stone\", \"at\": [1, 1]}",
                "{\"block\": \"stone\", \"from\": [1, 1, 1]}",
                "{\"block\": \"stone\", \"from\": [5, 1, 1], \"to\": [1, 1, 1]}",
                "{\"block\": \"stone\", \"at\": [0, 1, 4]}",
                "{\"block\": \"stone\", \"at\": [15, 1, 4]}",
                "{\"block\": \"stone\", \"at\": [4, 1, 0]}",
                "{\"block\": \"stone\", \"at\": [4, 6, 4]}",
                "{\"block\": \"stone\", \"at\": [4, -9, 4]}",
                "{\"block\": \"stone\", \"from\": [1, 1, 1], \"to\": [2, 1, 1], \"count\": 0}",
                "{\"at\": [4, 1, 4]}",
                "{\"block\": \" \", \"at\": [4, 1, 4]}"}) {
            boolean threw = false;
            try {
                parse("{\"template\": \"pocketdungeons:rooms/seam\", \"roles\": [\"corridor\"], \"nodes\": [" + bad + "]}");
            } catch (IllegalArgumentException e) {
                threw = true;
                if (!e.getMessage().contains("seam")) {
                    throw new AssertionError("a bad node names the room: " + e.getMessage());
                }
            }
            check(threw, true, "bad node rejected: " + bad);
        }
        // A lower story position is fine for a two story room; nodes must be an array.
        check(parse("""
                {"template": "t", "roles": ["corridor"], "spanY": 2,
                 "nodes": [{"block": "stone", "at": [4, -5, 4]}]}
                """).nodes.size(), 1, "lower story node accepted");
        boolean threw = false;
        try {
            parse("{\"template\": \"t\", \"roles\": [\"corridor\"], \"nodes\": {}}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, true, "nodes must be an array");
    }

    private static void testW4ChoiceRejections() {
        for (String body : new String[]{"\"light\": \"pitch\"", "\"corridor\": \"tight\"", "\"acts\": [0]",
                "\"acts\": [6]"}) {
            boolean threw = false;
            try {
                parse("{\"template\": \"pocketdungeons:rooms/hall_straight\", \"roles\": [\"corridor\"], " + body + "}");
            } catch (IllegalArgumentException e) {
                threw = true;
                if (!e.getMessage().contains("hall_straight")) {
                    throw new AssertionError("rejection names the room: " + e.getMessage());
                }
            }
            check(threw, true, "rejected: " + body);
        }
        // graphRole is kept as written; PackValidator reports a stranger.
        check(parse("{\"template\": \"t\", \"roles\": [\"corridor\"], \"graphRole\": [\"boss\"]}").graphRole,
                java.util.List.of("boss"), "graphRole kept as written");
    }

    /** theme keeps working: with no dungeons field it stands in, matched against the main theme. */
    private static void testW4ThemeStandsInForDungeons() {
        DungeonRoomMeta themed = parse("""
                {"template": "t", "roles": ["corridor"], "theme": ["rootworks"]}
                """);
        check(themed.theme, java.util.List.of("rootworks"), "theme still parses");
        check(themed.usableByMainTheme("pocketdungeons:rootworks", "pocketdungeons:rootworks"), true,
                "bare theme matches the qualified main theme");
        check(themed.usableByMainTheme("pocketdungeons:ossuary", "pocketdungeons:ossuary"), false,
                "another main theme does not match");
        DungeonRoomMeta any = parse("{\"template\": \"t\", \"roles\": [\"corridor\"]}");
        check(any.usableByMainTheme("pocketdungeons:ossuary", "pocketdungeons:ossuary"), true, "no theme or dungeons means any");
        DungeonRoomMeta dungeons = parse("""
                {"template": "t", "roles": ["corridor"], "theme": ["ossuary"], "dungeons": ["rootworks"]}
                """);
        check(dungeons.usableByMainTheme("pocketdungeons:rootworks", "pocketdungeons:rootworks"), true,
                "dungeons wins over theme");
        check(dungeons.usableByMainTheme("pocketdungeons:ossuary", "pocketdungeons:ossuary"), false,
                "theme is ignored once dungeons is declared");
    }

    private static String parseWindow(String value) {
        return parse("""
                {
                  "template": "pocketdungeons:rooms/hall_straight",
                  "roles": ["corridor"],
                  "window": "%s"
                }
                """.formatted(value)).window;
    }

    private static DungeonRoomMeta parse(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        return DungeonRoomMeta.fromJson(obj);
    }

    private static void check(Object actual, Object expected, String what) {
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (!equal) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
