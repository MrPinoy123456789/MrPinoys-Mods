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
