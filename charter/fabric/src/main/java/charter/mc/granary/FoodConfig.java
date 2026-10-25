package charter.mc.granary;

import charter.food.FoodTable;
import charter.food.ShelfClass;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code food.json} (PLAN.md §6.5): item id to a shelf class, and optionally a
 * nourishment that overrides the vanilla food component's. Read-or-create like the
 * other config: a missing file is written from the jar, a file that does not parse
 * is left alone and the jar's table is used until it is fixed.
 *
 * <p>This only fills core's {@link FoodTable}. Core decides what is eaten and what
 * spoils from it.
 */
public final class FoodConfig {

    public static final String FILE_NAME = "food.json";
    public static final String RESOURCE = "/data/charter/food.json";

    private FoodConfig() {
    }

    /** The jar's own table as text. */
    public static String defaults() {
        try (InputStream in = FoodConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }

    /** Reads {@code config/charter/food.json} (writing it first if missing) into the table. */
    public static void load(Path configDir, FoodTable into, Function<String, OptionalDouble> vanilla,
                            Consumer<String> info, Consumer<String> warn) {
        Path file = configDir.resolve(FILE_NAME);
        String text;
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(configDir);
                Files.writeString(file, defaults(), StandardCharsets.UTF_8);
                info.accept("Wrote the food table to " + file);
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); using the built in food table.");
            text = defaults();
        }
        try {
            apply(text, into, vanilla, warn);
        } catch (JsonParseException | IllegalStateException e) {
            warn.accept(FILE_NAME + " could not be used (" + e.getMessage() + "); using the built in food table. The file was left as it is.");
            apply(defaults(), into, vanilla, warn);
        }
    }

    /**
     * Fills the table from the text. An id the registry does not know, or that is
     * neither a food nor given a nourishment, is skipped with a warning; one bad
     * line never loses the rest. Throws for text that is not a JSON object.
     */
    public static void apply(String text, FoodTable into, Function<String, OptionalDouble> vanilla, Consumer<String> warn) {
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) throw new IllegalStateException("expected an object of item ids");
        for (var entry : root.getAsJsonObject().entrySet()) {
            String id = entry.getKey();
            if (id.startsWith("_")) continue;
            try {
                JsonObject o = entry.getValue().getAsJsonObject();
                ShelfClass shelf = ShelfClass.valueOf(o.get("shelf").getAsString().toUpperCase(java.util.Locale.ROOT));
                OptionalDouble nourishment = o.has("nourishment") ? OptionalDouble.of(o.get("nourishment").getAsDouble()) : vanilla.apply(id);
                if (nourishment.isEmpty() || !(nourishment.getAsDouble() > 0)) {
                    warn.accept(FILE_NAME + ": " + id + " is not a food the game knows, or has no nourishment; skipped.");
                    continue;
                }
                into.put(id, nourishment.getAsDouble(), shelf);
            } catch (RuntimeException e) {
                warn.accept(FILE_NAME + ": " + id + " could not be read (" + e.getMessage() + "); skipped.");
            }
        }
    }
}
