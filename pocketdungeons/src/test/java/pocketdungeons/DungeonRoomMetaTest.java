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
