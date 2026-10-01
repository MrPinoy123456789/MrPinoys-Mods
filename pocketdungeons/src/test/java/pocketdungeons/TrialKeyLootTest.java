package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * PD-87: every trial key the shipped loot tables hand out is a bare item.
 *
 * <p>A vault matches its key by item and components together, and two stacks
 * only merge when both agree, so a key carrying anything extra (the chest
 * tables used to stamp {@code custom_data.pocketdungeons.bag} on it) neither
 * opens a vault nor stacks with a key from a trial spawner, whose table
 * ({@code spawners/trial_key}) has always been bare. Keys from a chest, a
 * vault, a spawner and a room pot are the same item only if no loot entry
 * dresses them up, which is what this asserts, file by file.
 *
 * <p>Runs headless over the source tree, like {@link BagTableTest}.
 */
public class TrialKeyLootTest {

    private static final Path LOOT_ROOT = Path.of("src/main/resources/data/pocketdungeons/loot_table");

    private static final Set<String> KEYS = Set.of("minecraft:trial_key", "minecraft:ominous_trial_key");

    public static void main(String[] args) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(LOOT_ROOT)) {
            files = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        if (files.isEmpty()) {
            throw new AssertionError("no loot tables under " + LOOT_ROOT.toAbsolutePath());
        }
        int keyEntries = 0;
        List<String> dressed = new ArrayList<>();
        for (Path file : files) {
            JsonElement root;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader);
            }
            keyEntries += scan(root, LOOT_ROOT.relativize(file).toString(), dressed);
        }
        if (keyEntries == 0) {
            throw new AssertionError("found no trial key entries at all; the scan is looking in the wrong place");
        }
        if (!dressed.isEmpty()) {
            throw new AssertionError("trial keys must be bare so they stack and open vaults (PD-87); "
                    + "these entries add functions: " + String.join(", ", dressed));
        }
        System.out.println("TrialKeyLootTest passed (" + keyEntries + " key entries in " + files.size() + " tables)");
    }

    /** Counts the key entries under {@code node}, recording each one that carries a function. */
    private static int scan(JsonElement node, String file, List<String> dressed) {
        int found = 0;
        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            if (obj.has("type") && "minecraft:item".equals(obj.get("type").getAsString())
                    && obj.has("name") && KEYS.contains(obj.get("name").getAsString())) {
                found++;
                JsonArray functions = obj.getAsJsonArray("functions");
                if (functions != null && !functions.isEmpty()) {
                    dressed.add(file + " (" + obj.get("name").getAsString() + ")");
                }
            }
            for (var entry : obj.entrySet()) {
                found += scan(entry.getValue(), file, dressed);
            }
        } else if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                found += scan(child, file, dressed);
            }
        }
        return found;
    }
}
