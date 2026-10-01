package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PD-95: the bag tag on a stackable reward stops it merging with the same item
 * from anywhere else, so a chest's iron ingots sat beside the pack's iron
 * ingots in a second stack. Only unstackable items carry the tag now.
 */
public final class LootTagGameTest {

    /**
     * No shipped loot table puts a bare bag tag on an item that stacks. Item
     * stack sizes come from the live registry, so this cannot drift from the
     * hand-kept lists in the generator and the strip script.
     */
    @GameTest(maxTicks = 20)
    public void noStackableLootCarriesTheBagTag(GameTestHelper helper) {
        Map<Identifier, Resource> tables = helper.getLevel().getServer().getResourceManager()
                .listResources("loot_table", id -> id.getNamespace().equals(PocketDungeonsMod.MOD_ID)
                        && id.getPath().endsWith(".json"));
        helper.assertTrue(!tables.isEmpty(), "the mod ships loot tables");
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> table : tables.entrySet()) {
            try (BufferedReader reader = table.getValue().openAsReader()) {
                walk(JsonParser.parseReader(reader), table.getKey().toString(), offenders);
            } catch (java.io.IOException e) {
                helper.fail("could not read " + table.getKey() + ": " + e.getMessage());
                return;
            }
        }
        helper.assertTrue(offenders.isEmpty(), "stackable loot tagged with bag: " + offenders);
        helper.succeed();
    }

    /**
     * PD-94: no loot table hands out another mod's token. The Kamu Totems
     * Boss Stones dropped as inert echo shards on a server without that mod.
     */
    @GameTest(maxTicks = 20)
    public void noLootCarriesAnotherModsToken(GameTestHelper helper) {
        Map<Identifier, Resource> tables = helper.getLevel().getServer().getResourceManager()
                .listResources("loot_table", id -> id.getNamespace().equals(PocketDungeonsMod.MOD_ID)
                        && id.getPath().endsWith(".json"));
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> table : tables.entrySet()) {
            try (BufferedReader reader = table.getValue().openAsReader()) {
                String text = reader.lines().reduce("", String::concat);
                if (text.contains("kamutotems")) {
                    offenders.add(table.getKey().toString());
                }
            } catch (java.io.IOException e) {
                helper.fail("could not read " + table.getKey() + ": " + e.getMessage());
                return;
            }
        }
        helper.assertTrue(offenders.isEmpty(), "loot naming kamutotems: " + offenders);
        helper.succeed();
    }

    private static void walk(JsonElement element, String table, List<String> offenders) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> walk(child, table, offenders));
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if ("minecraft:item".equals(string(object, "type")) && object.has("functions")) {
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(string(object, "name")))
                    .orElse(Items.AIR);
            if (item.getDefaultMaxStackSize() > 1 && hasBareBagTag(object)) {
                offenders.add(table + " " + string(object, "name"));
            }
        }
        object.entrySet().forEach(child -> walk(child.getValue(), table, offenders));
    }

    /** A {@code set_components} whose custom data is exactly {@code {pocketdungeons:{bag:1}}}. */
    private static boolean hasBareBagTag(JsonObject entry) {
        for (JsonElement function : entry.getAsJsonArray("functions")) {
            JsonObject components = function.getAsJsonObject().getAsJsonObject("components");
            JsonObject customData = components == null ? null : components.getAsJsonObject("minecraft:custom_data");
            if (customData != null && customData.size() == 1) {
                JsonObject mine = customData.getAsJsonObject(PocketDungeonsMod.MOD_ID);
                if (mine != null && mine.size() == 1 && mine.has("bag")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * A stack an older table tagged comes back on entry without the tag, so it
     * merges with plain stacks; tagged gear and stacks carrying more than the
     * bag tag are left alone.
     */
    @GameTest(maxTicks = 20)
    public void enteringStripsTheBagTagFromStackables(GameTestHelper helper) {
        ItemStack tagged = bagTagged(new ItemStack(Items.IRON_INGOT, 3), null);
        ItemStack plain = new ItemStack(Items.IRON_INGOT, 14);
        helper.assertTrue(!ItemStack.isSameItemSameComponents(tagged, plain),
                "a tagged ingot does not merge with a plain one (the PD-95 symptom)");

        ItemStack stripped = InventorySwap.withoutStackableBagTag(tagged);
        helper.assertTrue(ItemStack.isSameItemSameComponents(stripped, plain),
                "the stripped ingot merges with a plain one");
        helper.assertTrue(stripped.getCount() == 3, "stripping keeps the count");

        ItemStack sword = bagTagged(new ItemStack(Items.IRON_SWORD), null);
        helper.assertTrue(InventorySwap.isBagTagged(InventorySwap.withoutStackableBagTag(sword)),
                "an unstackable reward keeps its tag");

        ItemStack token = bagTagged(new ItemStack(Items.ECHO_SHARD), "shellUnlock");
        helper.assertTrue(InventorySwap.isBagTagged(InventorySwap.withoutStackableBagTag(token)),
                "a stack carrying more than the bag tag keeps it");
        helper.succeed();
    }

    private static ItemStack bagTagged(ItemStack stack, String extraKey) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag mine = new CompoundTag();
            mine.putInt("bag", 1);
            if (extraKey != null) {
                mine.putString(extraKey, "sandstone");
            }
            tag.put(PocketDungeonsMod.MOD_ID, mine);
        });
        return stack;
    }
}
