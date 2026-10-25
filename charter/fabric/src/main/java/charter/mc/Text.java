package charter.mc;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every player-facing string (PLAN.md §7.5), from {@code data/charter/lang/en_us.json}
 * in the jar. On first boot the file is copied to {@code config/charter/en_us.json}
 * so a server can rewrite the voice; keys missing there keep the jar's text, so a
 * new release's keys always show.
 *
 * <p>Placeholders are named, {@code {org}}, and filled from name and value pairs.
 */
public final class Text {

    public static final String RESOURCE = "/data/charter/lang/en_us.json";
    public static final String FILE_NAME = "en_us.json";
    private static final Type MAP = new TypeToken<LinkedHashMap<String, String>>() { }.getType();
    private static final Gson GSON = new Gson();

    private static final Map<String, String> TEXT = new LinkedHashMap<>(defaults());

    private Text() {
    }

    /** The jar's own strings. */
    public static Map<String, String> defaults() {
        try (InputStream in = Text.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the jar");
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), MAP);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }

    /** Copies the defaults out on first boot, then reads the server's copy over them. */
    public static void load(Path configDir, Consumer<String> info, Consumer<String> warn) {
        Map<String, String> merged = new LinkedHashMap<>(defaults());
        Path file = configDir.resolve(FILE_NAME);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(configDir);
                try (InputStream in = Text.class.getResourceAsStream(RESOURCE)) {
                    Files.copy(in, file);
                }
                info.accept("Wrote player text to " + file);
            } else {
                Map<String, String> server = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MAP);
                if (server != null) merged.putAll(server);
            }
        } catch (IOException | JsonParseException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); using the built in text.");
        }
        TEXT.clear();
        TEXT.putAll(merged);
    }

    public static boolean has(String key) {
        return TEXT.containsKey(key);
    }

    /** The text for a key with {@code {name}} placeholders filled from pairs: {@code get("k", "org", "Oakvale")}. */
    public static String get(String key, Object... pairs) {
        String text = TEXT.get(key);
        if (text == null) return key;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return text;
    }

    /** As {@link #get(String, Object...)} with the values in a map. */
    public static String fill(String key, Map<String, ?> values) {
        String text = TEXT.get(key);
        if (text == null) return null;
        for (Map.Entry<String, ?> e : values.entrySet()) text = text.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        return text;
    }
}
