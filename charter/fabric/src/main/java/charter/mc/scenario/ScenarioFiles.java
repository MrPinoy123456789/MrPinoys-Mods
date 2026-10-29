package charter.mc.scenario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The scenario files in {@code config/charter/scenarios/}. The tutorial and the five
 * the plan names (PLAN.md §12.3) ship in the jar and are written out on first use, so an operator
 * can change or copy them; a file already there is never overwritten.
 */
public final class ScenarioFiles {

    public static final List<String> DEFAULTS = List.of("tutorial", "jail");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ScenarioFiles() {
    }

    /** Writes any default that is missing. Returns how many were written. */
    public static int writeDefaults(Path dir, Consumer<String> warn) {
        int written = 0;
        try {
            Files.createDirectories(dir);
            for (String name : DEFAULTS) {
                Path file = dir.resolve(name + ".json");
                if (Files.exists(file)) continue;
                try (InputStream in = ScenarioFiles.class.getResourceAsStream("/charter/scenarios/" + name + ".json")) {
                    if (in == null) {
                        warn.accept("The default scenario " + name + " is missing from the jar.");
                        continue;
                    }
                    Files.write(file, in.readAllBytes());
                    written++;
                }
            }
        } catch (IOException e) {
            warn.accept("Could not write the default scenarios: " + e.getMessage());
        }
        return written;
    }

    /** Every scenario in the folder, by name. A file that does not parse is skipped with a warning. */
    public static TreeMap<String, Scenario> all(Path dir, Consumer<String> warn) {
        TreeMap<String, Scenario> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".json")) continue;
                String name = fileName.substring(0, fileName.length() - ".json".length()).toLowerCase(Locale.ROOT);
                read(file, warn).ifPresent(s -> {
                    if (s.name == null || s.name.isBlank()) s.name = name;
                    out.put(name, s);
                });
            }
        } catch (IOException e) {
            warn.accept("Could not list the scenarios: " + e.getMessage());
        }
        return out;
    }

    static Optional<Scenario> read(Path file, Consumer<String> warn) {
        try {
            return Optional.ofNullable(parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | JsonParseException e) {
            warn.accept("Skipped the scenario " + file.getFileName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses one scenario. Public so the plain tests can read the shipped defaults. */
    public static Scenario parse(String json) {
        return GSON.fromJson(json, Scenario.class);
    }
}
