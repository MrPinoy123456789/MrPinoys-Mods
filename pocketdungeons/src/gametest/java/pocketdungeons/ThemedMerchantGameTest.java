package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Themed store merchants: the merchant follows the floor's theme, prices its
 * stock in that floor's mob drops, and a theme without a merchant keeps the
 * original emerald-only shopkeeper. Reads the villager's saved inventory tag,
 * the same string the shop GUI loads, rather than opening the GUI (nothing
 * headless clicks an SGUI chest).
 */
@SuppressWarnings("removal")
public final class ThemedMerchantGameTest {

    private static final String INVENTORY_PREFIX = "pocketdungeons_store_inv:";

    @GameTest
    public void anOssuaryFloorHostsABoneCollectorPricedInBones(GameTestHelper helper) {
        int bonePriced = 0;
        for (long seed = 1; seed <= 12; seed++) {
            Villager villager = spawnStore(helper, "pocketdungeons:ossuary", seed);
            helper.assertValueEqual(villager.getName().getString(), "Bone Collector", "merchant for ossuary");
            for (String currency : currencies(villager)) {
                helper.assertTrue(currency.equals("minecraft:bone") || currency.equals("minecraft:emerald"),
                        "an ossuary merchant prices in bones or emeralds, saw " + currency);
                if (currency.equals("minecraft:bone")) {
                    bonePriced++;
                }
            }
            villager.discard();
        }
        helper.assertTrue(bonePriced > 0, "twelve ossuary stores priced at least one item in bones");
        helper.succeed();
    }

    @GameTest
    public void aNetherFloorHostsANetherMerchantThatTakesBlazeRodsAndMagmaCream(GameTestHelper helper) {
        for (long seed = 1; seed <= 12; seed++) {
            Villager villager = spawnStore(helper, "basalt_foundry", seed);
            helper.assertValueEqual(villager.getName().getString(), "Blaze Trader", "merchant for basalt foundry");
            for (String currency : currencies(villager)) {
                helper.assertTrue(currency.equals("minecraft:blaze_rod")
                                || currency.equals("minecraft:magma_cream")
                                || currency.equals("minecraft:emerald"),
                        "a nether merchant prices in its floor's drops or emeralds, saw " + currency);
            }
            villager.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void aThemeWithoutAMerchantKeepsTheEmeraldShopkeeper(GameTestHelper helper) {
        for (String theme : new String[] {null, "pocketdungeons:prismarine", "somepack:unheard_of"}) {
            Villager villager = spawnStore(helper, theme, 5);
            helper.assertValueEqual(villager.getName().getString(), "Wandering Merchant", "default merchant");
            for (String currency : currencies(villager)) {
                helper.assertValueEqual(currency, "minecraft:emerald", "default merchant prices in emeralds");
            }
            villager.discard();
        }
        helper.succeed();
    }

    /** A merchant never sells the drop it buys with, and never lists an item twice. */
    @GameTest
    public void aStoreNeverSellsItsOwnCurrencyOrRepeatsAnItem(GameTestHelper helper) {
        for (long seed = 1; seed <= 20; seed++) {
            Villager villager = spawnStore(helper, "ender_archive", seed);
            List<String> sold = new ArrayList<>();
            for (String entry : entries(villager)) {
                String item = entry.split(",", 5)[0];
                helper.assertTrue(!item.equals("minecraft:ender_pearl"),
                        "the archivist buys pearls, so it does not sell them");
                helper.assertTrue(!sold.contains(item), "no item listed twice, saw " + item + " again");
                sold.add(item);
            }
            helper.assertTrue(sold.size() >= 4 && sold.size() <= StorePricing.MAX_SLOTS,
                    "a store lists four to seven items, saw " + sold.size());
            villager.discard();
        }
        helper.succeed();
    }

    /**
     * PD-159: a purchase is paid from the pack in one go. Short payment sells
     * nothing, a full payment takes exactly the price (even spread over several
     * stacks), hands over the plain item and lowers the stock by one, and a line
     * bought out says sold out and takes nothing.
     */
    @GameTest
    public void aSaleTakesExactlyThePriceAndLowersStockUntilSoldOut(GameTestHelper helper) {
        Villager villager = spawnStore(helper, null, 3);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        String[] fields = entries(villager).get(0).split(",", 5);
        int price = Integer.parseInt(fields[2]);
        int stock = Integer.parseInt(fields[3]);
        Inventory pack = player.getInventory();

        pack.clearContent();
        pack.setItem(0, new ItemStack(Items.EMERALD, price - 1));
        helper.assertTrue(StoreNPC.buy(player, villager, 0) == StoreNPC.Sale.SHORT,
                "one emerald short is not a sale");
        helper.assertValueEqual(stockOf(villager, 0), stock, "a refused sale leaves the stock alone");
        helper.assertValueEqual(pack.getItem(0).getCount(), price - 1, "and takes nothing");

        pack.clearContent();
        int firstHalf = price / 2;
        pack.setItem(3, new ItemStack(Items.EMERALD, firstHalf));
        pack.setItem(20, new ItemStack(Items.EMERALD, price - firstHalf + 2));
        helper.assertTrue(StoreNPC.buy(player, villager, 0) == StoreNPC.Sale.BOUGHT,
                "a price spread over two stacks buys");
        helper.assertValueEqual(count(pack, Items.EMERALD), 2, "exactly the price was taken from the pack");
        helper.assertValueEqual(stockOf(villager, 0), stock - 1, "buying lowers the stock by one");

        while (stockOf(villager, 0) > 0) {
            pack.setItem(0, new ItemStack(Items.EMERALD, price));
            helper.assertTrue(StoreNPC.buy(player, villager, 0) == StoreNPC.Sale.BOUGHT, "buying down the stock");
        }
        pack.clearContent();
        pack.setItem(0, new ItemStack(Items.EMERALD, price));
        helper.assertTrue(StoreNPC.buy(player, villager, 0) == StoreNPC.Sale.SOLD_OUT, "a line bought out is sold out");
        helper.assertValueEqual(pack.getItem(0).getCount(), price, "and takes no payment");
        helper.assertTrue(StoreNPC.buy(player, villager, 99) == StoreNPC.Sale.GONE, "no such line");
        villager.discard();
        helper.succeed();
    }

    /** A line's display: the real item, the price gold or red, the stock, and a gray pane once sold out. */
    @GameTest
    public void aLineShowsPriceAndStockAndSellsOutAsAPane(GameTestHelper helper) {
        StoreNPC.ShopEntry stocked = new StoreNPC.ShopEntry(Items.IRON_SWORD, null, "Iron Sword",
                MerchantThemes.EMERALD, 5, 3);
        ItemStack shown = StoreNPC.displayFor(stocked, 5);
        helper.assertTrue(shown.is(Items.IRON_SWORD), "the line shows the real item");
        helper.assertTrue(!shown.has(DataComponents.CUSTOM_NAME), "under its own name");
        List<net.minecraft.network.chat.Component> lore = shown.get(DataComponents.LORE).lines();
        helper.assertValueEqual(lore.get(0).getString(), "5 emeralds", "the price");
        helper.assertValueEqual(lore.get(1).getString(), "3 left", "the stock");
        helper.assertTrue(lore.get(0).getStyle().getColor().equals(
                net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.GOLD)), "gold when affordable");
        ItemStack poor = StoreNPC.displayFor(stocked, 4);
        helper.assertTrue(poor.get(DataComponents.LORE).lines().get(0).getStyle().getColor().equals(
                net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.RED)), "red when short");
        helper.assertValueEqual(StoreNPC.priceText(1, MerchantThemes.EMERALD), "1 emerald", "a price of one");

        StoreNPC.ShopEntry gone = new StoreNPC.ShopEntry(Items.IRON_SWORD, null, "Iron Sword",
                MerchantThemes.EMERALD, 5, 0);
        ItemStack pane = StoreNPC.displayFor(gone, 99);
        helper.assertTrue(!pane.is(Items.IRON_SWORD), "a sold out line no longer shows the item");
        helper.assertValueEqual(pane.getHoverName().getString(), "sold out", "it says sold out");
        helper.succeed();
    }

    private static int count(Inventory pack, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (pack.getItem(i).is(item)) {
                n += pack.getItem(i).getCount();
            }
        }
        return n;
    }

    private static int stockOf(Villager villager, int line) {
        return Integer.parseInt(entries(villager).get(line).split(",", 5)[3]);
    }

    private static Villager spawnStore(GameTestHelper helper, String theme, long seed) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        StoreNPC.spawn(level, origin, seed, theme);
        List<Villager> found = level.getEntitiesOfClass(Villager.class,
                new AABB(origin).inflate(RoomGeometry.CELL),
                v -> v.entityTags().contains(StoreNPC.STORE_TAG) && entries(v).size() > 0);
        if (found.isEmpty()) {
            helper.fail("StoreNPC.spawn left no store villager for theme " + theme);
        }
        return found.get(0);
    }

    private static List<String> entries(Villager villager) {
        for (String tag : villager.entityTags()) {
            if (tag.startsWith(INVENTORY_PREFIX)) {
                String data = tag.substring(INVENTORY_PREFIX.length());
                return data.isEmpty() ? List.of() : List.of(data.split(";"));
            }
        }
        return List.of();
    }

    /** The currency item id of each saved entry ({@code item,currency,price,stock,name}). */
    private static List<String> currencies(Villager villager) {
        List<String> out = new ArrayList<>();
        for (String entry : entries(villager)) {
            out.add(entry.split(",", 5)[1]);
        }
        return out;
    }
}
