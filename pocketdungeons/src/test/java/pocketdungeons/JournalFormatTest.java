package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure regression for the playtest journal's line format
 * ({@code docs/PLAYTEST_EVENTS.md}): common fields first and in order, extras
 * after, one line per event, torn lines skipped by the reader, the retention
 * window, and the context snapshot's pure builder. Run from {@code tasks.test}.
 */
public class JournalFormatTest {

    public static void main(String[] args) {
        testTimestamp();
        testLineShape();
        testExtrasTypes();
        testTornLines();
        testTail();
        testRetention();
        testFirstLine();
        testContextSnapshot();
        System.out.println("JournalFormatTest passed");
    }

    private static JournalFormat.Common common() {
        return new JournalFormat.Common("2026-09-27T18:04:05Z", "00000000-0000-0000-0000-000000000001",
                "Bob", 3, "ACTIVE", 2, "pocketdungeons:ossuary", 1);
    }

    private static void testTimestamp() {
        checkEquals(JournalFormat.timestamp(Instant.parse("2026-09-27T18:04:05.987Z")), "2026-09-27T18:04:05Z");
    }

    private static void testLineShape() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("room", "pocketdungeons:hold_the_plate");
        extras.put("cell", "1,-2");
        extras.put("slot", 99); // a common field: must not be written twice
        String line = JournalFormat.line("room_entered", common(), extras);
        checkEquals(line, "{\"t\":\"2026-09-27T18:04:05Z\",\"ev\":\"room_entered\","
                + "\"player\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"Bob\",\"slot\":3,"
                + "\"phase\":\"ACTIVE\",\"floor\":2,\"zone\":\"pocketdungeons:ossuary\",\"party\":1,"
                + "\"room\":\"pocketdungeons:hold_the_plate\",\"cell\":\"1,-2\"}");
        check(!line.contains("\n"), "a line never contains a line break");
        // Chat text keeps its angle brackets readable, and newlines are escaped.
        String chat = JournalFormat.line("lemon_ask", common(), Map.of("text", "<hi> what now?\nsecond"));
        check(chat.contains("\"text\":\"<hi> what now?\\nsecond\""), "text escaped as JSON, not HTML: " + chat);
        check(!chat.contains("\n"), "an embedded newline stays escaped");
    }

    private static void testExtrasTypes() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("affixes", List.of("ominous", "feral"));
        extras.put("granted", Map.of("minecraft:torch", 8));
        extras.put("score", null);
        extras.put("ok", true);
        JsonObject o = JsonParser.parseString(JournalFormat.line("kit_topup", common(), extras)).getAsJsonObject();
        check(o.get("affixes").getAsJsonArray().size() == 2, "arrays written as arrays");
        check(o.get("granted").getAsJsonObject().get("minecraft:torch").getAsInt() == 8, "maps as objects");
        check(o.get("score").isJsonNull(), "null as null");
        check(o.get("ok").getAsBoolean(), "booleans as booleans");
        check(o.get("slot").getAsInt() == 3, "ints stay ints");
    }

    private static void testTornLines() {
        check(JournalFormat.parse("{\"t\":\"x\",\"ev\":\"bank\",\"flo") == null, "a torn line is skipped");
        check(JournalFormat.parse("") == null, "a blank line is skipped");
        check(JournalFormat.parse("not json") == null, "garbage is skipped");
        check(JournalFormat.parse("{\"t\":\"x\"}") == null, "an object with no ev is skipped");
        check(JournalFormat.parse("[1,2]") == null, "a non-object is skipped");
        JsonObject ok = JournalFormat.parse("{\"t\":\"x\",\"ev\":\"bank\"}");
        check(ok != null && ok.get("ev").getAsString().equals("bank"), "a whole line parses");
    }

    private static void testTail() {
        List<String> lines = List.of(
                "{\"ev\":\"a\"}", "{\"ev\":\"b\"}", "torn{", "{\"ev\":\"c\"}", "{\"ev\":\"d\"", "");
        List<JsonObject> last = JournalFormat.tail(lines, 2);
        check(last.size() == 2, "two events");
        checkEquals(last.get(0).get("ev").getAsString(), "b");
        checkEquals(last.get(1).get("ev").getAsString(), "c");
        check(JournalFormat.tail(lines, 10).size() == 3, "only whole events come back");
        check(JournalFormat.tail(List.of(), 5).isEmpty(), "an empty file reads as nothing");
    }

    private static void testRetention() {
        LocalDate today = LocalDate.parse("2026-09-27");
        check(!JournalFormat.expired("2026-09-27", today), "today is kept");
        check(!JournalFormat.expired("2026-08-29", today), "the 30th day back is kept");
        check(JournalFormat.expired("2026-08-28", today), "the 31st day back goes");
        check(!JournalFormat.expired("notes", today), "a folder that is not a date is left alone");
    }

    private static void testFirstLine() {
        checkEquals(JournalFormat.firstLine("boom\r\n\tat x.y(Z.java:1)"), "boom");
        checkEquals(JournalFormat.firstLine(null), "");
        checkEquals(JournalFormat.firstLine("single"), "single");
    }

    private static void testContextSnapshot() {
        PlayerContext.Snapshot s = new PlayerContext.Snapshot("2026-09-27T18:04:05Z",
                "00000000-0000-0000-0000-000000000001", "Bob", "ACTIVE", 3, 2, "pocketdungeons:ossuary",
                7, 8, 1, "pocketdungeons:void", "pocketdungeons:hold_the_plate", "1,0",
                List.of(new PlayerContext.RoomView("0,0", "pocketdungeons:entry_hall", "entrance", true, 0, 0, false),
                        new PlayerContext.RoomView("1,0", "pocketdungeons:hold_the_plate", "puzzle", true, 1, 2, true),
                        new PlayerContext.RoomView("2,0", "pocketdungeons:hold_the_plate", "puzzle", false, 0, 0, false)),
                2, 5, PlayerContext.bandSoFar(5, 2), 2, 3, 5, 6,
                List.of(new PlayerContext.ToolView("minecraft:stone_pickaxe", 40, 131)), 64, 5, 2, true,
                new Lemon.View(true, true, "llm", false, false, 1, false, "How was that floor?"),
                List.of(JournalFormat.parse("{\"ev\":\"door_commit\",\"step\":2}")));
        JsonObject o = PlayerContext.toJson(s);
        List<String> keys = List.copyOf(o.keySet());
        checkEquals(String.join(" ", keys), "t player name phase slot floor zone keystone run_level party dimension "
                + "room room_cell rooms omen spawners inventory lemon recent");
        check(o.get("rooms").getAsJsonArray().size() == 3, "every room on the floor");
        JsonObject here = o.get("rooms").getAsJsonArray().get(1).getAsJsonObject();
        check(here.get("here").getAsBoolean(), "the room the player stands in is marked");
        check(!o.get("rooms").getAsJsonArray().get(2).getAsJsonObject().get("here").getAsBoolean(),
                "the same room id in another cell is not");
        check(here.get("locked").getAsBoolean() && here.get("spawners").getAsJsonObject().get("total").getAsInt() == 2,
                "spawner and lock state");
        check(o.get("omen").getAsJsonObject().get("band").getAsInt() == Omen.band(5, 2), "band so far");
        check(o.get("spawners").getAsJsonObject().get("needed").getAsInt() == 5, "the gate");
        check(o.get("inventory").getAsJsonObject().get("near_full").getAsBoolean(), "pack near full");
        checkEquals(o.get("lemon").getAsJsonObject().get("mode").getAsString(), "llm");
        checkEquals(o.get("recent").getAsJsonArray().get(0).getAsJsonObject().get("ev").getAsString(), "door_commit");
        check(!o.toString().contains("\n"), "one line of JSON");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void checkEquals(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but was '" + actual + "'");
        }
    }
}
