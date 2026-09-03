package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SITUATIONS_SPEC 3.2's bag authoring rules, turned into assertions.
 *
 * <p>The rules are "every bag has food", "no bag carries armour or a weapon
 * better than stone", "Pilgrim is bread and nothing else", and (spec 11.9)
 * "every item carries the {@code pocketdungeons.bag} tag". They are content
 * rules, not code rules, so nothing in Java enforces them and a pack author
 * adding a ninth bag has no other guard rail. Parsing the shipped JSON here is
 * the cheapest place they will ever be checked, and it runs without a server.
 *
 * <p>The eight ids are written out below rather than read from {@link Bags}
 * deliberately. This test asserts that what ships matches the spec; taking the
 * list from the class under test would only assert that the code agrees with
 * itself, and a bag quietly dropped from both would pass.
 *
 * <p>Failure messages name the bag and the rule, because whoever trips one of
 * these is editing a datapack and not this file.
 */
public class BagTableTest {

    /** Spec 3.2's eight archetypes, in the order the spec table lists them. */
    private static final List<String> BAG_IDS = List.of(
            "mason", "plumber", "sapper", "magician", "ranger", "shepherd", "innkeeper", "pilgrim");

    private static final String RESOURCE_ROOT = "/data/pocketdungeons/loot_table/bags/";
    private static final String SOURCE_ROOT = "src/main/resources/data/pocketdungeons/loot_table/bags/";

    /**
     * What counts as food for rule 1. Deliberately a short list of what the
     * shipped bags actually carry plus the obvious neighbours, not every edible
     * item in the game: a bag that feeds its player on suspicious stew is a bag
     * worth failing until somebody says otherwise in this list.
     */
    private static final Set<String> FOOD = Set.of(
            "minecraft:bread", "minecraft:cooked_beef", "minecraft:cooked_porkchop",
            "minecraft:cooked_cod", "minecraft:cooked_salmon", "minecraft:baked_potato",
            "minecraft:golden_carrot", "minecraft:golden_apple", "minecraft:milk_bucket");

    private static final String[] ARMOUR_SUFFIXES = {"_helmet", "_chestplate", "_leggings", "_boots"};

    /** Armour that does not end in one of the four suffixes. */
    private static final Set<String> ARMOUR_EXTRA = Set.of(
            "minecraft:turtle_helmet", "minecraft:elytra", "minecraft:shield");

    /**
     * Weapon materials above stone. Rule 2 bans the weapon, not the material, so
     * a stone pickaxe is fine and a golden sword is not; the Ranger's bow is
     * explicitly a puzzle tool in spec 3.2 and is allowed.
     */
    private static final String[] ABOVE_STONE = {"golden_", "iron_", "diamond_", "netherite_"};

    private static final Set<String> WEAPONS_OUTRIGHT = Set.of(
            "minecraft:trident", "minecraft:mace");

    public static void main(String[] args) {
        for (String bag : BAG_IDS) {
            JsonObject table = load(bag);
            List<String> items = itemsOf(bag, table);
            checkNotEmpty(bag, items);
            checkHasFood(bag, items);
            checkNoArmour(bag, items);
            checkNoBetterThanStone(bag, items);
            checkTagged(bag, table);
        }
        checkPilgrimIsBreadOnly();
        System.out.println("BagTableTest passed (" + BAG_IDS.size() + " bags)");
    }

    /** Rule: every bag id resolves to a parsable table. */
    private static JsonObject load(String bag) {
        String json = read(bag);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new AssertionError("bag " + bag + ": table did not parse as JSON: " + e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) {
            throw new AssertionError("bag " + bag + ": table is not a JSON object");
        }
        JsonObject table = parsed.getAsJsonObject();
        if (!table.has("pools")) {
            throw new AssertionError("bag " + bag + ": table has no pools, so it hands out nothing");
        }
        return table;
    }

    /**
     * The bag's table, off the classpath when there is one and off the source
     * tree when there is not. Both, because this runs two ways: as a Gradle
     * {@code JavaExec} with the mod's resources on the classpath, and by hand
     * from the mod root while somebody is editing the JSON.
     */
    private static String read(String bag) {
        String name = bag + ".json";
        try (InputStream stream = BagTableTest.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (stream != null) {
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    StringBuilder out = new StringBuilder();
                    char[] buffer = new char[8192];
                    int read;
                    while ((read = reader.read(buffer)) >= 0) {
                        out.append(buffer, 0, read);
                    }
                    return out.toString();
                }
            }
        } catch (IOException e) {
            throw new AssertionError("bag " + bag + ": could not read " + RESOURCE_ROOT + name, e);
        }
        Path onDisk = Path.of(SOURCE_ROOT + name);
        if (!Files.isRegularFile(onDisk)) {
            throw new AssertionError("bag " + bag + ": no loot table at " + RESOURCE_ROOT + name
                    + " on the classpath and none at " + onDisk.toAbsolutePath());
        }
        try {
            return Files.readString(onDisk, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("bag " + bag + ": could not read " + onDisk, e);
        }
    }

    /** Every {@code minecraft:item} entry's name, in table order, duplicates kept. */
    private static List<String> itemsOf(String bag, JsonObject table) {
        List<String> names = new ArrayList<>();
        for (JsonObject entry : entriesOf(table)) {
            if (!entry.has("name")) {
                throw new AssertionError("bag " + bag + ": an item entry has no name");
            }
            names.add(entry.get("name").getAsString().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** Every {@code minecraft:item} entry anywhere in the table, nesting included. */
    private static List<JsonObject> entriesOf(JsonObject table) {
        List<JsonObject> out = new ArrayList<>();
        collect(table.getAsJsonArray("pools"), out);
        return out;
    }

    private static void collect(JsonArray array, List<JsonObject> out) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            if (node.has("type") && "minecraft:item".equals(node.get("type").getAsString())) {
                out.add(node);
            }
            collect(node.getAsJsonArray("entries"), out);
            collect(node.getAsJsonArray("children"), out);
        }
    }

    private static void checkNotEmpty(String bag, List<String> items) {
        if (items.isEmpty()) {
            throw new AssertionError("bag " + bag + ": table contains no items at all");
        }
    }

    /** Spec 3.2 rule 1: every bag includes food. Hunger is not the puzzle. */
    private static void checkHasFood(String bag, List<String> items) {
        for (String item : items) {
            if (FOOD.contains(item)) {
                return;
            }
        }
        throw new AssertionError("bag " + bag + " carries no food (spec 3.2 rule 1: every bag "
                + "includes food, because hunger is not the puzzle). Items: " + items);
    }

    /** Spec 3.2 rule 2, first half: a bag never contains armour. */
    private static void checkNoArmour(String bag, List<String> items) {
        for (String item : items) {
            if (isArmour(item)) {
                throw new AssertionError("bag " + bag + " carries armour (" + item + "). Spec 3.2 "
                        + "rule 2: combat is solved with the room, not the kit.");
            }
        }
    }

    private static boolean isArmour(String item) {
        if (ARMOUR_EXTRA.contains(item)) {
            return true;
        }
        for (String suffix : ARMOUR_SUFFIXES) {
            if (item.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Spec 3.2 rule 2, second half: no weapon better than stone. */
    private static void checkNoBetterThanStone(String bag, List<String> items) {
        for (String item : items) {
            if (WEAPONS_OUTRIGHT.contains(item)) {
                throw new AssertionError("bag " + bag + " carries " + item + ", a weapon above "
                        + "stone. Spec 3.2 rule 2 caps a bag's weapon at stone.");
            }
            if (!item.endsWith("_sword") && !item.endsWith("_axe")) {
                continue;
            }
            String bare = item.substring("minecraft:".length());
            for (String material : ABOVE_STONE) {
                if (bare.startsWith(material)) {
                    throw new AssertionError("bag " + bag + " carries " + item + ", a weapon above "
                            + "stone. Spec 3.2 rule 2 caps a bag's weapon at stone.");
                }
            }
        }
    }

    /** Spec 11.9: every bag item carries the {@code pocketdungeons.bag} byte. */
    private static void checkTagged(String bag, JsonObject table) {
        for (JsonObject entry : entriesOf(table)) {
            String name = entry.get("name").getAsString();
            if (!hasBagTag(entry)) {
                throw new AssertionError("bag " + bag + ": " + name + " has no "
                        + "custom_data.pocketdungeons.bag. Spec 11.9 uses that tag to tell what "
                        + "the mod handed out from what another mod injected, so an untagged bag "
                        + "item is delivered to the room with a warning instead of carried.");
            }
        }
    }

    private static boolean hasBagTag(JsonObject entry) {
        JsonArray functions = entry.getAsJsonArray("functions");
        if (functions == null) {
            return false;
        }
        for (JsonElement element : functions) {
            JsonObject function = element.getAsJsonObject();
            if (!"minecraft:set_components".equals(string(function, "function"))) {
                continue;
            }
            JsonObject components = function.getAsJsonObject("components");
            if (components == null) {
                continue;
            }
            JsonObject customData = components.getAsJsonObject("minecraft:custom_data");
            if (customData == null) {
                continue;
            }
            JsonObject mine = customData.getAsJsonObject("pocketdungeons");
            if (mine != null && mine.has("bag") && mine.get("bag").getAsInt() == 1) {
                return true;
            }
        }
        return false;
    }

    private static String string(JsonObject object, String field) {
        return object.has(field) ? object.get(field).getAsString() : null;
    }

    /**
     * Spec 3.2 rule 3 at its sharpest end: Pilgrim solves nothing, so its table
     * is bread and nothing else. This is the bag the generator is tested
     * against, and one quietly helpful addition to it stops being that.
     */
    private static void checkPilgrimIsBreadOnly() {
        List<String> items = itemsOf("pilgrim", load("pilgrim"));
        for (String item : items) {
            if (!"minecraft:bread".equals(item)) {
                throw new AssertionError("bag pilgrim carries " + item + ". Spec 3.2: Pilgrim is "
                        + "bread and nothing else, and every tool must come from a room.");
            }
        }
        if (items.isEmpty()) {
            throw new AssertionError("bag pilgrim carries no bread at all");
        }
    }
}
