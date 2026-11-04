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
 * openable), and a wild floor ignores the theme's own suffix and override. To compete with farms, tier 3 and up add
 * a war tier no farm makes, and every vault pays a stack of golden apples. Gold is the kingdom server's money
 * (owner, 2026-11-04), so every tier pays Gold Sovereigns: a gold nugget is only ever a Sovereign, carrying Charter
 * Currency's marker, name and lore, and every vault always pays some.
 */
public class WildLootTest {

    private static final Path ROOT = Path.of("src/main/resources/data/pocketdungeons/loot_table");
    private static final Set<String> SUPPLIES = Set.of("minecraft:gold_ingot", "minecraft:golden_apple",
            "minecraft:iron_ingot", "minecraft:emerald", "minecraft:nether_wart", "minecraft:trial_key",
            "minecraft:gold_nugget");
    /** A Gold Sovereign is a gold nugget with this marker (chartercurrency/SPEC.md 4.1). */
    private static final String SOVEREIGN_ITEM = "minecraft:gold_nugget";
    /** What no farm makes (owner, 2026-11-02, option A): tier 3 (level 10) and up only. */
    private static final Set<String> WAR_TIER = Set.of("minecraft:enchanted_golden_apple", "minecraft:diamond",
            "minecraft:netherite_scrap", "minecraft:ancient_debris", "minecraft:book");
    private static final Set<String> BOOKS = Set.of("minecraft:protection=4", "minecraft:sharpness=5");

    public static void main(String[] args) throws Exception {
        for (int tier = 1; tier <= 4; tier++) {
            for (String base : List.of(LootTables.tierTable(tier, false), LootTables.tierTable(tier, true),
                    LootTables.supplyTable(tier), LootTables.vaultTable(tier, false), LootTables.vaultTable(tier, true))) {
                Path file = ROOT.resolve(base + WildLoot.SUFFIX + ".json");
                check(Files.exists(file), "a wild twin exists for " + base);
                JsonElement table = JsonParser.parseString(Files.readString(file));
                Set<String> items = new TreeSet<>();
                collect(table, items);
                check(!items.isEmpty(), base + " wild pays something");
                for (String item : items) {
                    boolean war = WAR_TIER.contains(item) && tier >= 3;
                    check(SUPPLIES.contains(item) || war, base + " wild carries " + item + " at tier " + tier);
                }
                Set<String> books = new TreeSet<>();
                collectBooks(table, books);
                check(BOOKS.containsAll(books), base + " wild pays only Protection IV and Sharpness V books: " + books);
                checkSovereigns(table, base);
            }
            Set<String> chest = new TreeSet<>();
            collect(JsonParser.parseString(Files.readString(ROOT.resolve(LootTables.tierTable(tier, false) + "_wild.json"))), chest);
            check(chest.containsAll(Set.of("minecraft:gold_ingot", "minecraft:golden_apple", "minecraft:iron_ingot",
                    "minecraft:emerald", "minecraft:nether_wart")), "a tier " + tier + " chest can pay all five supplies");
            if (tier >= 3) {
                check(chest.containsAll(WAR_TIER), "a tier " + tier + " chest can pay the whole war tier");
            }
            // Golden apples come by the stack: every vault pays them, unconditionally.
            JsonObject vault = JsonParser.parseString(Files.readString(
                    ROOT.resolve(LootTables.vaultTable(tier, false) + "_wild.json"))).getAsJsonObject();
            boolean apples = false;
            for (JsonElement pool : vault.getAsJsonArray("pools")) {
                Set<String> inPool = new TreeSet<>();
                collect(pool, inPool);
                apples |= inPool.equals(Set.of("minecraft:golden_apple")) && !pool.getAsJsonObject().has("condition");
            }
            check(apples, "a tier " + tier + " vault always pays golden apples");
            check(chest.contains(SOVEREIGN_ITEM), "a tier " + tier + " chest can pay Gold Sovereigns");
            for (boolean ominous : new boolean[]{false, true}) {
                JsonObject anyVault = JsonParser.parseString(Files.readString(
                        ROOT.resolve(LootTables.vaultTable(tier, ominous) + "_wild.json"))).getAsJsonObject();
                boolean sovereigns = false;
                for (JsonElement pool : anyVault.getAsJsonArray("pools")) {
                    Set<String> inPool = new TreeSet<>();
                    collect(pool, inPool);
                    sovereigns |= inPool.equals(Set.of(SOVEREIGN_ITEM)) && !pool.getAsJsonObject().has("condition");
                }
                check(sovereigns, "a tier " + tier + (ominous ? " ominous" : "") + " vault always pays Gold Sovereigns");
            }
        }
        check(WildLoot.SUFFIX.equals(WildLoot.suffix(true, "_drowned")), "a wild floor ignores the theme suffix");
        check("_drowned".equals(WildLoot.suffix(false, "_drowned")), "a campaign floor keeps the theme suffix");
        check(WildLoot.override(true, "mypack:vaults/x") == null, "a wild floor ignores the theme override");
        check("mypack:vaults/x".equals(WildLoot.override(false, "mypack:vaults/x")), "a campaign floor keeps the override");
        System.out.println("WildLootTest passed");
    }

    /** Every gold nugget entry is a real Sovereign: Charter Currency's marker, the Mint's name and its lore. */
    private static void checkSovereigns(JsonElement element, String table) {
        if (element.isJsonArray()) {
            for (JsonElement e : (JsonArray) element) {
                checkSovereigns(e, table);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject o = element.getAsJsonObject();
        if (o.has("type") && "minecraft:item".equals(o.get("type").getAsString()) && o.has("name")
                && SOVEREIGN_ITEM.equals(o.get("name").getAsString())) {
            String modifiers = String.valueOf(o.get("modifier"));
            check(modifiers.contains("\"chartercurrency\":{\"coin\":\"gold\"}"),
                    table + " wild pays a gold nugget without the Sovereign marker: " + modifiers);
            check(modifiers.contains("\"target\":\"item_name\"") && modifiers.contains("\"Gold Sovereign\""),
                    table + " wild pays a Sovereign without its item name: " + modifiers);
            check(modifiers.contains("Charter coin, worth 64 Marks"),
                    table + " wild pays a Sovereign without the Mint's lore line: " + modifiers);
            return;
        }
        for (String key : o.keySet()) {
            checkSovereigns(o.get(key), table);
        }
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

    /** Every set_enchantments modifier's enchantments, as {@code id=level}. */
    private static void collectBooks(JsonElement element, Set<String> books) {
        if (element.isJsonArray()) {
            for (JsonElement e : (JsonArray) element) {
                collectBooks(e, books);
            }
        } else if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            if (o.has("type") && "minecraft:set_enchantments".equals(o.get("type").getAsString())) {
                for (String id : o.getAsJsonObject("enchantments").keySet()) {
                    books.add(id + "=" + o.getAsJsonObject("enchantments").get(id).getAsInt());
                }
            }
            for (String key : o.keySet()) {
                collectBooks(o.get(key), books);
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
