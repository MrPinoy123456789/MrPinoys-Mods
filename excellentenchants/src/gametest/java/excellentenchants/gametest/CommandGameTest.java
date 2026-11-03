package excellentenchants.gametest;

import excellentenchants.Catalog;
import excellentenchants.Enchants;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.enchantment.Enchantment;

/** {@code /enchants}: the list matches the data pack, pages are right, and the command runs for anyone. */
public final class CommandGameTest {

    @GameTest
    public void theListMatchesTheDataPack(GameTestHelper helper) throws Exception {
        var registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        TagKey<Enchantment> table = TagKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace("non_treasure"));
        Set<Object> keys = new HashSet<>();
        for (Field f : Enchants.class.getFields()) {
            if (f.get(null) instanceof ResourceKey<?> key && !excellentenchants.Retired.KEYS.contains(key)) keys.add(key);
        }
        Set<Object> listed = new HashSet<>();
        for (Catalog.Entry e : Catalog.ALL) {
            ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT,
                    Identifier.fromNamespaceAndPath("excellentenchants", e.id()));
            listed.add(key);
            var holder = registry.get(key).orElse(null);
            helper.assertTrue(holder != null, "not registered: " + e.id());
            helper.assertTrue(holder.value().getMaxLevel() == e.maxLevel(), e.id() + " max level " + holder.value().getMaxLevel());
            helper.assertTrue(holder.is(table) == e.table(), e.id() + " table flag does not match the non_treasure tag");
            for (String line : Catalog.details(e)) {
                helper.assertTrue(!line.contains("—") && !line.contains(" -- "), e.id() + " has a dash: " + line);
            }
        }
        // Every enchantment except the retired ones (Silk Chest) is listed.
        helper.assertTrue(listed.equals(keys) && listed.size() == 14, "the list and Enchants differ: " + listed + " vs " + keys);
        helper.succeed();
    }

    @GameTest
    public void pagesHoldFiveAndClamp(GameTestHelper helper) {
        helper.assertTrue(Catalog.pages() == 3, "pages " + Catalog.pages());
        helper.assertTrue(Catalog.page(1).size() == 5 && Catalog.page(3).size() == 4, "five a page, four on the last");
        helper.assertTrue(Catalog.page(99).equals(Catalog.page(3)) && Catalog.page(0).equals(Catalog.page(1)), "clamps");
        helper.assertTrue(Catalog.find("Ice Aspect").isPresent() && Catalog.find("excellentenchants:thunder").isPresent()
                && Catalog.find("nope").isEmpty(), "find by name and id");
        helper.succeed();
    }

    @GameTest
    public void theCommandRunsForAnyPlayer(GameTestHelper helper) throws Exception {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        // A plain, non-operator player's own source: the command must need no permission.
        var source = helper.makeMockServerPlayerInLevel().createCommandSourceStack().withSuppressedOutput();
        helper.assertTrue(dispatcher.execute("enchants", source) == 1, "first page");
        helper.assertTrue(dispatcher.execute("enchants 2", source) == 2, "second page");
        helper.assertTrue(dispatcher.execute("enchants 9", source) == 3, "a page past the end shows the last");
        helper.assertTrue(dispatcher.execute("enchants info Ice Aspect", source) == 1, "info by name");
        helper.assertTrue(dispatcher.execute("enchants info nope", source) == 0, "unknown name");
        helper.succeed();
    }
}
