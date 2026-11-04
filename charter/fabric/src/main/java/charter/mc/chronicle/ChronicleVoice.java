package charter.mc.chronicle;

import charter.chronicle.DefaultTemplates;
import charter.chronicle.Template;
import charter.chronicle.TemplateSet;
import charter.chronicle.Tier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Chronicle's voice as a file a server can rewrite: {@code config/charter/templates.json},
 * one entry per event record name (PLAN.md section 7.5), read or created the way the rest of
 * the config is. An entry is either a plain string (the book line; the built in headline and
 * tier stay) or an object:
 *
 * <pre>"SiegeEvents.WarDeclared": {"line": "On {when}, ...", "headline": "{attacker} declared war ...", "tier": "realm"}</pre>
 *
 * <ul>
 * <li>Missing file: write the built in voice, use it.</li>
 * <li>A key missing from the file keeps the built in entry, so a new release's events
 * always have a voice. A line set to an empty string keeps that event out of every book.</li>
 * <li>A file that does not parse: log why, use the built in voice, never overwrite it.</li>
 * </ul>
 */
public final class ChronicleVoice {

    public static final String FILE_NAME = "templates.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ChronicleVoice() {
    }

    public static TemplateSet readOrCreate(Path dir, Consumer<String> info, Consumer<String> warn) {
        TemplateSet defaults = DefaultTemplates.get();
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(dir);
                Files.writeString(file, GSON.toJson(write(defaults)) + "\n", StandardCharsets.UTF_8);
                info.accept("Wrote the Chronicle's voice to " + file);
            } catch (IOException e) {
                warn.accept("Could not write " + file + " (" + e.getMessage() + "); using the built in voice.");
            }
            return defaults;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || root.isJsonNull() || !root.isJsonObject()) {
                warn.accept(file + " is empty; using the built in voice. The file was left as it is.");
                return defaults;
            }
            return defaults.overlay(read(root.getAsJsonObject(), warn));
        } catch (IOException | JsonParseException | IllegalStateException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); using the built in voice. The file was left as it is.");
            return defaults;
        }
    }

    /** The file's entries as a set; a line given as a string has no headline or tier of its own. */
    static TemplateSet read(JsonObject root, Consumer<String> warn) {
        TemplateSet mine = new TemplateSet();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonElement value = e.getValue();
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonPrimitive()) {
                mine.put(e.getKey(), value.getAsString());
            } else if (value.isJsonObject()) {
                JsonObject o = value.getAsJsonObject();
                String line = string(o, "line");
                if (line == null) line = string(o, "text");
                if (line == null) {
                    warn.accept("templates.json: " + e.getKey() + " has no line; the built in one is kept.");
                    continue;
                }
                String headline = string(o, "headline");
                Tier tier = Tier.parse(string(o, "tier")).orElse(null);
                if (o.has("tier") && tier == null) warn.accept("templates.json: " + e.getKey() + " has an unknown tier; the built in one is kept.");
                mine.put(e.getKey(), line, headline, tier);
            }
        }
        return mine;
    }

    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() || !v.isJsonPrimitive() ? null : v.getAsString();
    }

    /** The set as the file holds it: objects where there is a headline or tier, plain strings for the reserved lines. */
    static JsonObject write(TemplateSet set) {
        JsonObject out = new JsonObject();
        for (Template t : set.all().values()) {
            if (t.headline() == null && t.tier() == null) {
                out.addProperty(t.key(), t.text());
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("line", t.text());
            if (t.headline() != null) o.addProperty("headline", t.headline());
            if (t.tier() != null) o.addProperty("tier", t.tier().word());
            out.add(t.key(), o);
        }
        return out;
    }
}
