package ballot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened, appended and never rewritten.
 *
 * <p>The history is kept because votes change. When the outcome is a permanent town
 * monument, somebody will eventually dispute the result, and "who flipped on the last
 * day" is a question worth being able to answer.
 *
 * <p>Fields are a loose string map rather than a sealed hierarchy: this is written to
 * disk and read by humans far more often than it is read by code, and a new event type
 * should not require a schema migration.
 */
public final class PollEvent {

    private long t;
    private String type;
    private Map<String, String> data;

    PollEvent() {}   // Gson

    private PollEvent(String type, Map<String, String> data, long at) {
        this.t = at;
        this.type = type;
        this.data = data;
    }

    public static PollEvent of(String type, long at, String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must pair up");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return new PollEvent(type, map, at);
    }

    public long at() {
        return t;
    }

    public String type() {
        return type;
    }

    public String get(String key) {
        return data == null ? null : data.get(key);
    }

    public Map<String, String> data() {
        return data == null ? Map.of() : Map.copyOf(data);
    }
}
