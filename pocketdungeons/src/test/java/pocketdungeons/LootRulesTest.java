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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * K and K2 loot policy, pinned file by file against the shipped tables
 * (plan 2026-10-06-2, sections K and K2; the data twin of
 * {@code tools/loot_rules.py}, which the generators and the one-shot rewrite
 * share).
 *
 * <p>The assertions, in plan order:
 * <ul>
 *   <li>K: only the kept item set may appear; every cut (gardening, decor,
 *       stations, redstone, brewing leftovers, mob remnants, copper, the cut
 *       gear) is absent from every core table.</li>
 *   <li>K2.1: Sentry is the only trim anywhere; the other seventeen are the
 *       trims module's.</li>
 *   <li>K2.4: no gear in {@code chests/}; room tools, gold and pearls only in
 *       {@code supply_*}; golden apples and cake only in vaults, the finish
 *       ({@code tier_*}) chests and piglin bartering.</li>
 *   <li>K2.5: lapis sits in every tier 2 to 4 chest table.</li>
 *   <li>K2.7/K2.8: no copper ore in a dungeon {@code nodePalette},
 *       {@code hiddenOre} list or room {@code nodes} entry.</li>
 *   <li>K3: {@code gameplay/piglin_bartering} pays exactly the K3 table.</li>
 *   <li>L2: a module's own loot tables only carry items its manifest's
 *       {@code allow} list names.</li>
 * </ul>
 *
 * <p>{@code bags/} is exempt: a bag is authored starting kit, not loot the
 * world hands out, so the rare-food and tool rules do not apply to it.
 * {@code modules/} tables are checked against their manifest instead.
 * Runs headless over the source tree, like {@link TrialKeyLootTest}.
 */
public class LootRulesTest {

    private static final Path DATA_ROOT = Path.of("src/main/resources/data/pocketdungeons");
    private static final Path LOOT_ROOT = DATA_ROOT.resolve("loot_table");
    private static final Path MODULE_ROOT = DATA_ROOT.resolve("content_module");

    private static final String TRIM_SUFFIX = "_armor_trim_smithing_template";
    private static final String KEPT_TRIM = "minecraft:sentry" + TRIM_SUFFIX;
    private static final Set<String> SEED_SUFFIXES = Set.of("_seeds", "_sapling");
    private static final Set<String> GEAR_SUFFIXES = Set.of(
            "_helmet", "_chestplate", "_leggings", "_boots", "_sword", "_axe", "_spear");
    private static final Set<String> GEAR_EXACT = Set.of(
            "minecraft:bow", "minecraft:crossbow", "minecraft:shield");
    private static final Set<String> SUPPLY_ONLY = Set.of(
            "minecraft:shears", "minecraft:lead", "minecraft:bucket",
            "minecraft:water_bucket", "minecraft:lava_bucket", "minecraft:milk_bucket",
            "minecraft:gold_ingot", "minecraft:ender_pearl");
    private static final Set<String> RARE_FOOD = Set.of(
            "minecraft:cake", "minecraft:golden_apple", "minecraft:enchanted_golden_apple");

    /** Mirror of {@code CUT} in tools/loot_rules.py. Keep the two in step. */
    private static final Set<String> CUT = Set.of(
            "minecraft:wheat", "minecraft:hay_block", "minecraft:poisonous_potato",
            "minecraft:sugar_cane", "minecraft:beetroot", "minecraft:melon_slice",
            "minecraft:nether_wart", "minecraft:chorus_flower",
            "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:grass_block", "minecraft:moss_block", "minecraft:spore_blossom",
            "minecraft:vine",
            "minecraft:enchanting_table", "minecraft:brewing_stand", "minecraft:cauldron",
            "minecraft:redstone", "minecraft:piston", "minecraft:sticky_piston",
            "minecraft:repeater", "minecraft:comparator", "minecraft:rail",
            "minecraft:powered_rail", "minecraft:detector_rail", "minecraft:activator_rail",
            "minecraft:lightning_rod", "minecraft:observer", "minecraft:dispenser",
            "minecraft:dropper", "minecraft:hopper", "minecraft:redstone_torch",
            "minecraft:lever", "minecraft:target", "minecraft:tripwire_hook",
            "minecraft:daylight_detector", "minecraft:note_block",
            "minecraft:blaze_powder", "minecraft:blaze_rod", "minecraft:magma_cream",
            "minecraft:glass_bottle", "minecraft:glowstone_dust", "minecraft:ghast_tear",
            "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:nautilus_shell",
            "minecraft:heart_of_the_sea", "minecraft:powder_snow_bucket",
            "minecraft:ominous_bottle", "minecraft:wither_skeleton_skull",
            "minecraft:bone", "minecraft:string", "minecraft:leather", "minecraft:snowball",
            "minecraft:rotten_flesh", "minecraft:spider_eye",
            "minecraft:copper_ingot", "minecraft:raw_copper", "minecraft:copper_sword",
            "minecraft:copper_axe", "minecraft:copper_pickaxe", "minecraft:copper_shovel",
            "minecraft:copper_hoe", "minecraft:copper_helmet", "minecraft:copper_chestplate",
            "minecraft:copper_leggings", "minecraft:copper_boots",
            "minecraft:chainmail_helmet", "minecraft:chainmail_chestplate",
            "minecraft:chainmail_leggings", "minecraft:chainmail_boots",
            "minecraft:golden_helmet", "minecraft:golden_chestplate",
            "minecraft:golden_leggings", "minecraft:golden_boots",
            "minecraft:golden_sword", "minecraft:golden_axe", "minecraft:golden_pickaxe",
            "minecraft:golden_shovel", "minecraft:golden_hoe",
            "minecraft:trident", "minecraft:mace",
            "minecraft:iron_bars", "minecraft:oak_fence",
            "minecraft:saddle", "minecraft:netherite_upgrade_smithing_template");

    /** The K3 barter table: item to weight. */
    private static final Map<String, Integer> BARTER = Map.of(
            "minecraft:arrow", 30,
            "minecraft:gunpowder", 20,
            "minecraft:iron_ingot", 15,
            "minecraft:ender_pearl", 10,
            "minecraft:obsidian", 8,
            "minecraft:emerald", 10,
            "minecraft:gravel", 5,
            "minecraft:golden_apple", 2);

    public static void main(String[] args) throws IOException {
        testCoreTables();
        testBarterTable();
        testCopperOut();
        testModuleTables();
        System.out.println("LootRulesTest passed");
    }

    private static void testCoreTables() throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> lapisMissing = new ArrayList<>();
        int files = 0;
        Set<String> seen = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(LOOT_ROOT)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String rel = LOOT_ROOT.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("bags/") || rel.startsWith("affixes/")
                        || rel.startsWith("modules/") || rel.startsWith("gameplay/")) {
                    continue;
                }
                files++;
                JsonObject table = parse(file);
                Set<String> items = itemsOf(table);
                seen.addAll(items);
                String context = contextOf(rel);
                boolean finish = context.equals("chest") && rel.startsWith("chests/tier_");
                for (String name : items) {
                    if (banned(name)) {
                        problems.add(rel + " carries cut item " + name);
                        continue;
                    }
                    switch (context) {
                        case "supply" -> {
                            if (RARE_FOOD.contains(name)) {
                                problems.add(rel + ": rare food " + name + " does not belong in supply");
                            }
                        }
                        case "chest" -> {
                            if (isGear(name)) {
                                problems.add(rel + ": gear " + name + " does not belong in a chest (K2.4)");
                            }
                            if (isSupplyOnly(name)) {
                                problems.add(rel + ": room tool " + name + " pays from supply only (K2.5)");
                            }
                            if (RARE_FOOD.contains(name) && !finish) {
                                problems.add(rel + ": rare food " + name + " is vault and finish only (K2.4)");
                            }
                        }
                        case "vault" -> {
                            if (isSupplyOnly(name)) {
                                problems.add(rel + ": room tool " + name + " pays from supply only (K2.5)");
                            }
                        }
                        case "equip" -> {
                            if (isSupplyOnly(name) || RARE_FOOD.contains(name)) {
                                problems.add(rel + ": " + name + " does not belong in an equipment table");
                            }
                        }
                        default -> {
                            if (isGear(name) || isSupplyOnly(name) || RARE_FOOD.contains(name)) {
                                problems.add(rel + ": " + name + " does not belong in " + rel.split("/")[0]);
                            }
                        }
                    }
                }
                // K2.5: lapis in every tier 2 to 4 chest table. A layered
                // themed table picks it up through its nested base ref, so
                // only standalone tables need the entry themselves.
                if (rel.matches("chests/tier_[234].*\\.json") && !items.contains("minecraft:lapis_lazuli")
                        && !nestsBaseTier(table)) {
                    lapisMissing.add(rel);
                }
            }
        }
        check(files > 100, "expected the full table set under " + LOOT_ROOT + ", saw " + files);
        check(seen.contains("minecraft:bread") && seen.contains("minecraft:cooked_beef"),
                "the two staple foods survive: " + seen);
        check(seen.contains("minecraft:sand") && seen.contains("minecraft:gravel"),
                "sand and gravel are in the tables");
        check(problems.isEmpty(), "loot rule violations:\n  " + String.join("\n  ", problems));
        check(lapisMissing.isEmpty(), "tier 2 to 4 chest tables missing lapis: " + lapisMissing);
    }

    private static void testBarterTable() throws IOException {
        Path file = LOOT_ROOT.resolve("gameplay/piglin_bartering.json");
        check(Files.exists(file), "K3 table is missing: " + file);
        JsonObject table = parse(file);
        check("minecraft:barter".equals(table.get("type").getAsString()),
                "barter table type, got " + table.get("type"));
        JsonArray pools = table.getAsJsonArray("pools");
        check(pools.size() == 1, "one pool, one roll per ingot (K3), got " + pools.size());
        JsonObject pool = pools.get(0).getAsJsonObject();
        check(pool.get("rolls").getAsInt() == 1, "one roll per gold ingot (K3)");
        Map<String, Integer> found = new HashMap<>();
        for (JsonElement element : pool.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            check("minecraft:item".equals(entry.get("type").getAsString()),
                    "barter entries are bare items, got " + entry.get("type"));
            found.put(entry.get("name").getAsString(), entry.get("weight").getAsInt());
        }
        check(found.equals(BARTER), "barter table drifted from the K3 list: " + found);
    }

    private static void testCopperOut() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(DATA_ROOT.resolve("dungeon"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonObject obj = parse(file);
                String name = "dungeon/" + file.getFileName();
                JsonArray palette = obj.getAsJsonArray("nodePalette");
                if (palette != null) {
                    for (JsonElement block : palette) {
                        if (DungeonDef.isCopperOre(block.getAsString())) {
                            hits.add(name + " nodePalette: " + block.getAsString());
                        }
                    }
                }
                JsonObject hidden = obj.getAsJsonObject("hiddenOre");
                if (hidden != null && hidden.has("blocks")) {
                    for (JsonElement block : hidden.getAsJsonArray("blocks")) {
                        if (DungeonDef.isCopperOre(block.getAsString())) {
                            hits.add(name + " hiddenOre: " + block.getAsString());
                        }
                    }
                }
            }
        }
        try (Stream<Path> walk = Files.walk(DATA_ROOT.resolve("dungeon_room"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonObject obj = parse(file);
                JsonArray nodes = obj.getAsJsonArray("nodes");
                if (nodes == null) {
                    continue;
                }
                for (JsonElement node : nodes) {
                    JsonObject spec = node.getAsJsonObject();
                    if (spec.has("block") && DungeonDef.isCopperOre(spec.get("block").getAsString())) {
                        hits.add("dungeon_room/" + file.getFileName() + " node: " + spec.get("block"));
                    }
                }
            }
        }
        check(hits.isEmpty(), "copper ore crept back in (K2.7/K2.8):\n  " + String.join("\n  ", hits));
        check(DungeonDef.isCopperOre("copper_ore") && DungeonDef.isCopperOre("minecraft:deepslate_copper_ore"),
                "the copper check recognises bare and namespaced ore ids");
        check(!DungeonDef.isCopperOre("coal_ore"), "coal is not copper");
    }

    private static void testModuleTables() throws IOException {
        List<Path> manifests;
        try (Stream<Path> walk = Files.list(MODULE_ROOT)) {
            manifests = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        check(!manifests.isEmpty(), "no content module manifests under " + MODULE_ROOT);
        int tables = 0;
        List<String> problems = new ArrayList<>();
        for (Path manifest : manifests) {
            JsonObject module = parse(manifest);
            Set<String> allow = new HashSet<>();
            JsonArray allowList = module.getAsJsonArray("allow");
            if (allowList != null) {
                for (JsonElement item : allowList) {
                    allow.add(item.getAsString());
                }
            }
            JsonObject loot = module.getAsJsonObject("loot");
            if (loot == null) {
                continue;
            }
            Set<String> moduleTables = new TreeSet<>();
            for (Map.Entry<String, JsonElement> entry : loot.entrySet()) {
                moduleTables.add(entry.getValue().getAsString());
            }
            for (String tableId : moduleTables) {
                String path = tableId.contains(":") ? tableId.substring(tableId.indexOf(':') + 1) : tableId;
                Path tableFile = LOOT_ROOT.resolve(path + ".json");
                check(Files.exists(tableFile),
                        module.get("id") + " maps a loot table that does not exist: " + tableId);
                tables++;
                for (String item : itemsOf(parse(tableFile))) {
                    String bare = item.contains(":") ? item.substring(item.indexOf(':') + 1) : item;
                    if (!allow.contains(bare)) {
                        problems.add(module.get("id").getAsString() + " table " + tableId
                                + " carries " + item + ", not on its allow list");
                    }
                }
            }
        }
        check(tables > 0, "no module loot tables found to check");
        check(problems.isEmpty(), "module tables outside their allow lists:\n  "
                + String.join("\n  ", problems));
    }

    /** Whether the table nests a {@code chests/tier_*} base table it inherits lapis from. */
    private static boolean nestsBaseTier(JsonElement node) {
        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            if (obj.has("type") && "minecraft:loot_table".equals(obj.get("type").getAsString())
                    && obj.has("value") && obj.get("value").getAsString()
                            .matches(".*chests/tier_\\d(_ominous)?$")) {
                return true;
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (nestsBaseTier(entry.getValue())) {
                    return true;
                }
            }
        } else if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                if (nestsBaseTier(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String contextOf(String rel) {
        if (rel.startsWith("chests/supply_")) {
            return "supply";
        }
        if (rel.startsWith("chests/")) {
            return "chest";
        }
        if (rel.startsWith("vaults/")) {
            return "vault";
        }
        if (rel.startsWith("gear/") || rel.startsWith("equipment/")) {
            return "equip";
        }
        return "other";
    }

    private static boolean banned(String name) {
        if (CUT.contains(name)) {
            return true;
        }
        if (name.endsWith(TRIM_SUFFIX)) {
            return !name.equals(KEPT_TRIM);
        }
        for (String suffix : SEED_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGear(String name) {
        if (GEAR_EXACT.contains(name)) {
            return true;
        }
        for (String suffix : GEAR_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSupplyOnly(String name) {
        return SUPPLY_ONLY.contains(name) || name.endsWith("_boat");
    }

    private static JsonObject parse(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** Every {@code minecraft:item} entry's {@code name} in the table tree. */
    private static Set<String> itemsOf(JsonElement node) {
        Set<String> out = new HashSet<>();
        collect(node, out);
        return out;
    }

    private static void collect(JsonElement node, Set<String> out) {
        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            if (obj.has("type") && "minecraft:item".equals(obj.get("type").getAsString()) && obj.has("name")) {
                out.add(obj.get("name").getAsString());
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                collect(entry.getValue(), out);
            }
        } else if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                collect(child, out);
            }
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
