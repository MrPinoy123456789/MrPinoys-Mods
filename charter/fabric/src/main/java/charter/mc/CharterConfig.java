package charter.mc;

import charter.config.Knobs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads {@code config/charter/charter.json} into {@link Knobs}, the suite's
 * read-or-create way (docs/DESIGN.md §4.5).
 *
 * <ul>
 * <li>Missing file: write the defaults, log it, use them.</li>
 * <li>A key missing from the file: that knob keeps its default.</li>
 * <li>A file that does not parse, or parses to unusable values: log why, run
 * on defaults, and never overwrite the file, so the operator's edit survives
 * for them to fix.</li>
 * </ul>
 */
public final class CharterConfig {

    public static final String FILE_NAME = "charter.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private CharterConfig() {
    }

    public static Knobs readOrCreate(Path dir, Consumer<String> info, Consumer<String> warn) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Knobs defaults = new Knobs();
            try {
                Files.createDirectories(dir);
                Files.writeString(file, GSON.toJson(defaults) + "\n", StandardCharsets.UTF_8);
                info.accept("Wrote default config to " + file);
            } catch (IOException e) {
                warn.accept("Could not write " + file + " (" + e.getMessage() + "); running on defaults.");
            }
            return defaults;
        }

        Knobs read;
        try {
            read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Knobs.class);
        } catch (IOException | JsonParseException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); running on defaults. The file was left as it is.");
            return new Knobs();
        }
        if (read == null) {
            warn.accept(file + " is empty; running on defaults. The file was left as it is.");
            return new Knobs();
        }

        List<String> problems = read.problems();
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                warn.accept(FILE_NAME + ": " + problem);
            }
            warn.accept("Running on defaults until " + file + " is fixed. The file was left as it is.");
            return new Knobs();
        }
        info.accept("Loaded " + file);
        return read;
    }
}
