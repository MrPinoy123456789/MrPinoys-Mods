package charter.mc.chronicle;

import charter.chronicle.DefaultTemplates;
import charter.chronicle.TemplateSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Chronicle's voice as a file a server can rewrite: {@code config/charter/templates.json},
 * one line per event record name (PLAN.md section 7.5), read or created the way the rest of
 * the config is.
 *
 * <ul>
 * <li>Missing file: write the built in templates, use them.</li>
 * <li>A key missing from the file keeps the built in line, so a new release's events
 * always have a voice. A key set to an empty string keeps that event out of every book.</li>
 * <li>A file that does not parse: log why, use the built in voice, never overwrite it.</li>
 * </ul>
 */
public final class ChronicleVoice {

    public static final String FILE_NAME = "templates.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP = new TypeToken<LinkedHashMap<String, String>>() { }.getType();

    private ChronicleVoice() {
    }

    public static TemplateSet readOrCreate(Path dir, Consumer<String> info, Consumer<String> warn) {
        TemplateSet defaults = DefaultTemplates.get();
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Map<String, String> out = new LinkedHashMap<>();
            defaults.all().forEach((key, template) -> out.put(key, template.text()));
            try {
                Files.createDirectories(dir);
                Files.writeString(file, GSON.toJson(out) + "\n", StandardCharsets.UTF_8);
                info.accept("Wrote the Chronicle's voice to " + file);
            } catch (IOException e) {
                warn.accept("Could not write " + file + " (" + e.getMessage() + "); using the built in voice.");
            }
            return defaults;
        }
        try {
            Map<String, String> mine = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MAP);
            if (mine == null) {
                warn.accept(file + " is empty; using the built in voice. The file was left as it is.");
                return defaults;
            }
            mine.values().removeIf(java.util.Objects::isNull);
            return defaults.overlay(TemplateSet.of(mine));
        } catch (IOException | JsonParseException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); using the built in voice. The file was left as it is.");
            return defaults;
        }
    }
}
