package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The pure half of the playtest journal ({@code docs/PLAYTEST_EVENTS.md}): one
 * event as one line of JSON, the torn-line tolerant reader, and the retention
 * rule. No Minecraft types, so it is tested without a server
 * ({@code JournalFormatTest}).
 */
final class JournalFormat {

    private JournalFormat() {}

    /** Days of date folders kept; older ones are deleted on server start. */
    static final int RETENTION_DAYS = 30;

    /**
     * The fields every line carries, in the order they are written.
     *
     * @param t      ISO-8601 UTC, seconds precision
     * @param slot   instance slot, or -1 outside an instance
     * @param phase  {@code RunSession.Phase} name, or {@code NONE}
     * @param floor  floor index within the interval, or -1
     * @param zone   zone or theme id of the current floor, or empty
     * @param party  party size, 1 when solo
     */
    record Common(String t, String player, String name, int slot, String phase, int floor,
                  String zone, int party) {}

    /** The timestamp format of {@link Common#t}. */
    static String timestamp(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * One event as a single line of JSON (no line break). The common fields
     * come first, then {@code extras} in their own iteration order; an extra
     * that names a common field is skipped rather than written twice.
     */
    static String line(String ev, Common common, Map<String, ?> extras) {
        JsonObject o = new JsonObject();
        o.addProperty("t", common.t());
        o.addProperty("ev", ev);
        o.addProperty("player", common.player());
        o.addProperty("name", common.name());
        o.addProperty("slot", common.slot());
        o.addProperty("phase", common.phase());
        o.addProperty("floor", common.floor());
        o.addProperty("zone", common.zone() == null ? "" : common.zone());
        o.addProperty("party", common.party());
        if (extras != null) {
            for (Map.Entry<String, ?> e : extras.entrySet()) {
                if (!o.has(e.getKey())) {
                    o.add(e.getKey(), toJson(e.getValue()));
                }
            }
        }
        // JsonElement.toString is compact and not HTML-escaped, so chat text
        // with angle brackets stays readable in the file.
        return o.toString();
    }

    /**
     * Converts a plain Java value to JSON: null, strings, numbers, booleans,
     * maps (string keys), collections and arrays of those, and JSON elements
     * as they are. Anything else is written as its {@code toString}.
     */
    static JsonElement toJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement element) {
            return element;
        }
        if (value instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (value instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject o = new JsonObject();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                o.add(String.valueOf(e.getKey()), toJson(e.getValue()));
            }
            return o;
        }
        if (value instanceof Collection<?> items) {
            JsonArray a = new JsonArray();
            for (Object item : items) {
                a.add(toJson(item));
            }
            return a;
        }
        if (value instanceof Object[] items) {
            JsonArray a = new JsonArray();
            for (Object item : items) {
                a.add(toJson(item));
            }
            return a;
        }
        return new JsonPrimitive(value.toString());
    }

    /**
     * Parses one journal line, or returns null for anything that is not a
     * complete JSON object with an {@code ev}: a line torn by a crash, a blank
     * line, or garbage. Never throws.
     */
    static JsonObject parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(line);
            if (element.isJsonObject() && element.getAsJsonObject().has("ev")) {
                return element.getAsJsonObject();
            }
        } catch (RuntimeException e) {
            // Torn or malformed: skipped, never fatal.
        }
        return null;
    }

    /** The last {@code n} parseable events of {@code lines}, oldest first. */
    static List<JsonObject> tail(List<String> lines, int n) {
        List<JsonObject> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < n; i--) {
            JsonObject event = parse(lines.get(i));
            if (event != null) {
                out.add(0, event);
            }
        }
        return out;
    }

    /**
     * Whether a date folder named {@code folder} is older than the retention
     * window on {@code today}. A name that is not a date is never expired, so
     * a stray folder somebody put there is left alone.
     */
    static boolean expired(String folder, LocalDate today) {
        try {
            return LocalDate.parse(folder).isBefore(today.minusDays(RETENTION_DAYS - 1L));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The first line of a message, for the {@code error} event. */
    static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        String first = nl < 0 ? message : message.substring(0, nl);
        return first.endsWith("\r") ? first.substring(0, first.length() - 1) : first;
    }
}
