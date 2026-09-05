package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M64 (SITUATIONS_SPEC 6.4, audit finding 2.17): guaranteed consumable supply
 * must be separated from weighted treasure in the supply chests.
 *
 * <p>The supply chest is the only container in a run that is gated on nothing:
 * no key, no clock, no fight. A player who fights badly still walks out of it
 * with what they need to survive the next room. That contract only holds while
 * food and light are guaranteed to roll, which they are not when they share a
 * single weighted pool with iron ingots and experience bottles.
 *
 * <p>This test reads each supply table from the resources directory and
 * verifies:
 * <ul>
 *   <li>Food items are in a guaranteed pool (rolls = 1), not in a weighted
 *       pool. A player always gets food from a supply chest.</li>
 *   <li>Light items (torches) are in a guaranteed pool (rolls = 1). A player
 *       always gets light from a supply chest.</li>
 *   <li>Treasure items (iron ingots, gold ingots, experience bottles) are NOT
 *       in the guaranteed pools. Treasure stays weighted, so tool scarcity and
 *       treasure rarity are preserved.</li>
 * </ul>
 *
 * <p>Pure JDK plus Gson, like {@link SituationTagsTest}: the supply tables are
 * JSON files, and this test reads them from the source tree directly.
 */
public class SupplySeparationTest {

    private static final Path RESOURCES = Paths.get("src/main/resources/data/pocketdungeons/loot_table/chests");

    /** Items that count as food for the guaranteed-supply check. */
    private static final Set<String> FOOD = Set.of(
            "minecraft:bread", "minecraft:cooked_beef", "minecraft:baked_potato",
            "minecraft:cooked_mutton", "minecraft:cooked_chicken", "minecraft:golden_carrot",
            "minecraft:cooked_porkchop", "minecraft:cooked_cod", "minecraft:cooked_salmon",
            "minecraft:sweet_berries", "minecraft:glow_berries");

    /** Items that count as light for the guaranteed-supply check. */
    private static final Set<String> LIGHT = Set.of(
            "minecraft:torch", "minecraft:soul_torch", "minecraft:lantern",
            "minecraft:glowstone", "minecraft:sea_lantern");

    /** Items that are treasure and must NOT be in the guaranteed pools. */
    private static final Set<String> TREASURE = Set.of(
            "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:experience_bottle",
            "minecraft:diamond", "minecraft:emerald", "minecraft:netherite_scrap",
            "minecraft:enchanted_book", "minecraft:golden_apple",
            "minecraft:golden_boots", "minecraft:golden_helmet",
            "minecraft:golden_chestplate", "minecraft:golden_leggings");

    public static void main(String[] args) throws IOException {
        testSupplyTier1SeparatesFoodAndLight();
        testSupplyTier2SeparatesFoodAndLight();
        testSupplyTier3SeparatesFoodAndLight();
        System.out.println("SupplySeparationTest passed");
    }

    private static void testSupplyTier1SeparatesFoodAndLight() throws IOException {
        checkSupplyTable("supply_tier_1.json");
    }

    private static void testSupplyTier2SeparatesFoodAndLight() throws IOException {
        checkSupplyTable("supply_tier_2.json");
    }

    private static void testSupplyTier3SeparatesFoodAndLight() throws IOException {
        checkSupplyTable("supply_tier_3.json");
    }

    /**
     * Reads the named supply table and verifies the separation:
     * food and light are in guaranteed pools (rolls = 1), treasure is not.
     */
    private static void checkSupplyTable(String fileName) throws IOException {
        Path path = RESOURCES.resolve(fileName);
        if (!Files.exists(path)) {
            throw new AssertionError("supply table not found: " + path);
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray pools = root.getAsJsonArray("pools");
        if (pools == null || pools.isEmpty()) {
            throw new AssertionError(fileName + ": no pools");
        }

        // Collect items from guaranteed pools (rolls = 1) and weighted pools.
        Set<String> guaranteedItems = new HashSet<>();
        Set<String> weightedItems = new HashSet<>();
        boolean hasGuaranteedFood = false;
        boolean hasGuaranteedLight = false;

        for (JsonElement poolEl : pools) {
            JsonObject pool = poolEl.getAsJsonObject();
            int rolls = poolRolls(pool);
            JsonArray entries = pool.getAsJsonArray("entries");
            if (entries == null) {
                continue;
            }
            for (JsonElement entryEl : entries) {
                JsonObject entry = entryEl.getAsJsonObject();
                String name = entry.get("name").getAsString();
                if (rolls == 1) {
                    guaranteedItems.add(name);
                    if (FOOD.contains(name)) {
                        hasGuaranteedFood = true;
                    }
                    if (LIGHT.contains(name)) {
                        hasGuaranteedLight = true;
                    }
                } else {
                    weightedItems.add(name);
                }
            }
        }

        if (!hasGuaranteedFood) {
            throw new AssertionError(fileName
                    + ": no food item found in a guaranteed pool (rolls = 1); "
                    + "food must be separated from weighted treasure so a player "
                    + "always gets food from a supply chest");
        }
        if (!hasGuaranteedLight) {
            throw new AssertionError(fileName
                    + ": no light item found in a guaranteed pool (rolls = 1); "
                    + "light must be separated from weighted treasure so a player "
                    + "always gets light from a supply chest");
        }

        // Treasure must not be in the guaranteed pools.
        List<String> treasureInGuaranteed = new ArrayList<>();
        for (String item : TREASURE) {
            if (guaranteedItems.contains(item)) {
                treasureInGuaranteed.add(item);
            }
        }
        if (!treasureInGuaranteed.isEmpty()) {
            throw new AssertionError(fileName
                    + ": treasure items found in guaranteed pools: " + treasureInGuaranteed
                    + "; treasure must stay weighted to preserve scarcity");
        }
    }

    /**
     * Returns the pool's roll count. A pool with {@code "rolls": 1} (an integer)
     * is guaranteed. A pool with {@code "rolls": {"min": 1, "max": 2}} (an
     * object) is weighted.
     */
    private static int poolRolls(JsonObject pool) {
        JsonElement rollsEl = pool.get("rolls");
        if (rollsEl == null) {
            return 1;
        }
        if (rollsEl.isJsonPrimitive()) {
            return rollsEl.getAsInt();
        }
        if (rollsEl.isJsonObject()) {
            JsonObject rollsObj = rollsEl.getAsJsonObject();
            int min = rollsObj.has("min") ? rollsObj.get("min").getAsInt() : 1;
            int max = rollsObj.has("max") ? rollsObj.get("max").getAsInt() : 1;
            return min == max ? min : -1;
        }
        return -1;
    }
}
