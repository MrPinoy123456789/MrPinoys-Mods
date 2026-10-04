package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Playtest 2026-10-03 (A3): "I'm getting all this iron but no wood." The tier 1
 * chest tables carry oak planks, so wood is a possibility on the earliest
 * floors. The themed tables layer on these bases, so one entry each reaches
 * every theme.
 *
 * <p>Playtest 2026-10-03-2: fewer logs and more planks, so the shrub room is
 * the place to get logs. In every base chest table that carries both, planks
 * now outweigh logs, and the tier 2 tables carry planks too.
 *
 * <p>Runs headless over the source tree, like {@link TrialKeyLootTest}.
 */
public class ChestPlankLootTest {

    private static final Path CHESTS = Path.of("src/main/resources/data/pocketdungeons/loot_table/chests");

    public static void main(String[] args) throws IOException {
        for (String table : new String[]{"tier_1", "tier_1_ominous", "tier_2", "tier_2_ominous"}) {
            JsonObject planks = entry(table, "minecraft:oak_planks");
            if (planks == null) {
                throw new AssertionError(table + " has no minecraft:oak_planks entry");
            }
            JsonObject count = planks.getAsJsonArray("functions").get(0).getAsJsonObject().getAsJsonObject("count");
            if (count.get("min").getAsInt() != 2 || count.get("max").getAsInt() != 4) {
                throw new AssertionError(table + ": planks come 2 to 4 at a time, found " + count);
            }
        }
        for (String table : new String[]{"supply_tier_1", "tier_1", "tier_1_ominous", "tier_2", "tier_2_ominous"}) {
            int planks = entry(table, "minecraft:oak_planks").get("weight").getAsInt();
            JsonObject log = entry(table, "minecraft:oak_log");
            int logs = log == null ? 0 : log.get("weight").getAsInt();
            if (logs >= planks) {
                throw new AssertionError(table + ": logs (weight " + logs + ") should be rarer than planks ("
                        + planks + ")");
            }
        }
        System.out.println("ChestPlankLootTest passed");
    }

    private static JsonObject entry(String table, String item) throws IOException {
        try (Reader reader = Files.newBufferedReader(CHESTS.resolve(table + ".json"), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            for (JsonElement pool : root.getAsJsonObject().getAsJsonArray("pools")) {
                for (JsonElement e : pool.getAsJsonObject().getAsJsonArray("entries")) {
                    JsonObject entry = e.getAsJsonObject();
                    if (entry.has("name") && entry.get("name").getAsString().equals(item)) {
                        return entry;
                    }
                }
            }
        }
        return null;
    }
}
