package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wild dungeons pay war supplies (owner, 2026-11-02): gold, golden apples, iron, emeralds and nether wart. Every base
 * table a wild floor can resolve has a wild twin, the twins carry only those items (and a trial key so vaults stay
 * openable), and a wild floor ignores the theme's own suffix and override.
 */
public class WildLootTest {

    private static final Path ROOT = Path.of("src/main/resources/data/pocketdungeons/loot_table");
    private static final Set<String> ALLOWED = Set.of("minecraft:gold_ingot", "minecraft:golden_apple",
            "minecraft:iron_ingot", "minecraft:emerald", "minecraft:nether_wart", "minecraft:trial_key");

    public static void main(String[] args) throws Exception {
        for (int tier = 1; tier <= 4; tier++) {
            for (String base : List.of(LootTables.tierTable(tier, false), LootTables.tierTable(tier, true),
                    LootTables.supplyTable(tier), LootTables.vaultTable(tier, false), LootTables.vaultTable(tier, true))) {
                Path file = ROOT.resolve(base + WildLoot.SUFFIX + ".json");
                check(Files.exists(file), "a wild twin exists for " + base);
                Set<String> items = new TreeSet<>();
                collect(JsonParser.parseString(Files.readString(file)), items);
                check(!items.isEmpty(), base + " wild pays something");
                for (String item : items) {
                    check(ALLOWED.contains(item), base + " wild carries " + item + ", not a war supply");
                }
            }
            Set<String> chest = new TreeSet<>();
            collect(JsonParser.parseString(Files.readString(ROOT.resolve(LootTables.tierTable(tier, false) + "_wild.json"))), chest);
            check(chest.containsAll(Set.of("minecraft:gold_ingot", "minecraft:golden_apple", "minecraft:iron_ingot",
                    "minecraft:emerald", "minecraft:nether_wart")), "a tier " + tier + " chest can pay all five supplies");
        }
        check(WildLoot.SUFFIX.equals(WildLoot.suffix(true, "_drowned")), "a wild floor ignores the theme suffix");
        check("_drowned".equals(WildLoot.suffix(false, "_drowned")), "a campaign floor keeps the theme suffix");
        check(WildLoot.override(true, "mypack:vaults/x") == null, "a wild floor ignores the theme override");
        check("mypack:vaults/x".equals(WildLoot.override(false, "mypack:vaults/x")), "a campaign floor keeps the override");
        System.out.println("WildLootTest passed");
    }

    private static void collect(JsonElement element, Set<String> items) {
        if (element.isJsonArray()) {
            for (JsonElement e : (JsonArray) element) {
                collect(e, items);
            }
        } else if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            if (o.has("type") && "minecraft:item".equals(o.get("type").getAsString()) && o.has("name")) {
                items.add(o.get("name").getAsString());
            }
            for (String key : o.keySet()) {
                collect(o.get(key), items);
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
