package pocketdungeons;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import java.util.Set;

/**
 * K3 live server regression: a piglin barter rolled inside the dungeon pays
 * from {@code pocketdungeons:gameplay/piglin_bartering}; rolled anywhere else
 * it pays vanilla's own table. Both directions come from
 * {@link DungeonDrops}' {@code MODIFY_DROPS} hook, which swaps the drops only
 * when the roll's level is the dungeon dimension. Vanilla's table file is
 * never touched, so an overworld or Nether barter stays vanilla.
 *
 * <p>Determinism: the mod's table pays only the eight K3 items, so "every
 * drop is one of them" is a hard pass for the dungeon side. Vanilla's table
 * shares just obsidian, gravel and ender pearls with that set, so over thirty
 * rolls a vanilla run is practically guaranteed to surface a forbidden item,
 * and a dungeon run is practically guaranteed to surface one of the items
 * vanilla never pays (arrows, gunpowder, iron ingots, emeralds, golden
 * apples). The dungeon-dimension check is pointed at this test's level
 * through {@link DungeonDrops.Probe#useDimensionForTesting}, the same seam
 * {@link InventorySwap.Probe} provides for the swap tests, since a gametest
 * server loads no datapack dimensions.
 */
public final class PiglinBarterGameTest {

    private static final ResourceKey<LootTable> VANILLA_BARTER = ResourceKey.create(
            Registries.LOOT_TABLE, Identifier.parse("minecraft:gameplay/piglin_bartering"));

    private static final Set<Item> K3_ITEMS = Set.of(
            Items.ARROW, Items.GUNPOWDER, Items.IRON_INGOT, Items.ENDER_PEARL,
            Items.OBSIDIAN, Items.EMERALD, Items.GRAVEL, Items.GOLDEN_APPLE);

    /** Items the mod's table pays that vanilla's never does. */
    private static final Set<Item> MOD_ONLY = Set.of(
            Items.ARROW, Items.GUNPOWDER, Items.IRON_INGOT, Items.EMERALD, Items.GOLDEN_APPLE);

    private static final int ROLLS = 30;

    @GameTest
    public void dungeonBarterPaysTheKitTable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        LootTable barter = server.reloadableRegistries().getLootTable(VANILLA_BARTER);
        try {
            // Level counts as the dungeon: every drop must be a K3 item, and
            // over ROLLS rolls at least one must be something vanilla cannot
            // pay, or the roll never left the vanilla table at all.
            DungeonDrops.Probe.useDimensionForTesting(level.dimension());
            boolean sawModItem = false;
            for (int i = 0; i < ROLLS; i++) {
                for (ItemStack stack : roll(barter, level)) {
                    helper.assertTrue(K3_ITEMS.contains(stack.getItem()),
                            "dungeon barter paid " + stack.getItem()
                                    + ", which is not on the K3 table");
                    sawModItem |= MOD_ONLY.contains(stack.getItem());
                }
            }
            helper.assertTrue(sawModItem,
                    "over " + ROLLS + " dungeon rolls nothing vanilla-free appeared; "
                            + "the vanilla table is still paying");

            // Level counts as not the dungeon: the vanilla table must still
            // answer, so a vanilla-only item shows up within ROLLS rolls.
            DungeonDrops.Probe.useDimensionForTesting(net.minecraft.world.level.Level.NETHER);
            boolean sawVanillaItem = false;
            for (int i = 0; i < ROLLS; i++) {
                for (ItemStack stack : roll(barter, level)) {
                    if (!K3_ITEMS.contains(stack.getItem())) {
                        sawVanillaItem = true;
                    }
                }
            }
            helper.assertTrue(sawVanillaItem,
                    "over " + ROLLS + " non-dungeon rolls nothing outside the K3 set "
                            + "appeared; the vanilla table looks replaced");
        } finally {
            DungeonDrops.Probe.useDimensionForTesting(null);
        }
        helper.succeed();
    }

    private static Iterable<ItemStack> roll(LootTable table, ServerLevel level) {
        LootParams params = new LootParams.Builder(level)
                .create(LootContextParamSets.EMPTY);
        return table.getRandomItems(params, level.getRandom().nextLong());
    }
}
