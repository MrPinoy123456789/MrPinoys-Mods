package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
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
            helper.assertValueEqual(villager.getName().getString(), "Nether Merchant", "merchant for basalt foundry");
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
     * Playtest 2026-10-03 (L18): the shop is the villager trading screen. The
     * screen itself is client work; what is checkable headless is the sale it
     * runs. Short payment sells nothing, a full payment takes exactly the price
     * and lowers the stock by one, and a line bought out says sold out.
     */
    @GameTest
    public void aSaleTakesExactlyThePriceAndLowersStockUntilSoldOut(GameTestHelper helper) {
        Villager villager = spawnStore(helper, null, 3);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        String[] fields = entries(villager).get(0).split(",", 5);
        int price = Integer.parseInt(fields[2]);
        int stock = Integer.parseInt(fields[3]);

        SimpleContainer pay = new SimpleContainer(3);
        pay.setItem(0, new ItemStack(Items.EMERALD, price - 1));
        helper.assertTrue(StoreNPC.sell(player, villager, 0, pay) == StoreNPC.Sale.SHORT,
                "one emerald short is not a sale");
        helper.assertValueEqual(stockOf(villager, 0), stock, "a refused sale leaves the stock alone");

        pay.setItem(0, new ItemStack(Items.EMERALD, price + 2));
        helper.assertTrue(StoreNPC.sell(player, villager, 0, pay) == StoreNPC.Sale.BOUGHT, "full payment buys");
        helper.assertValueEqual(pay.getItem(0).getCount(), 2, "exactly the price was taken from the inputs");
        helper.assertValueEqual(stockOf(villager, 0), stock - 1, "buying lowers the stock by one");

        // Split payment across both inputs works the same.
        pay.clearContent();
        int firstHalf = price / 2;
        pay.setItem(0, new ItemStack(Items.EMERALD, firstHalf));
        pay.setItem(1, new ItemStack(Items.EMERALD, price - firstHalf));
        helper.assertTrue(StoreNPC.sell(player, villager, 0, pay) == StoreNPC.Sale.BOUGHT,
                "a price split across both input slots buys");
        helper.assertTrue(pay.getItem(0).isEmpty() && pay.getItem(1).isEmpty(), "and takes both halves");

        while (stockOf(villager, 0) > 0) {
            pay.setItem(0, new ItemStack(Items.EMERALD, price));
            helper.assertTrue(StoreNPC.sell(player, villager, 0, pay) == StoreNPC.Sale.BOUGHT, "buying down the stock");
        }
        pay.setItem(0, new ItemStack(Items.EMERALD, price));
        helper.assertTrue(StoreNPC.sell(player, villager, 0, pay) == StoreNPC.Sale.SOLD_OUT, "a line bought out is sold out");
        helper.assertValueEqual(pay.getItem(0).getCount(), price, "and takes no payment");
        helper.assertTrue(StoreNPC.sell(player, villager, 99, pay) == StoreNPC.Sale.GONE, "no such line");
        villager.discard();
        helper.succeed();
    }

    /** The offer a line becomes: stock as max uses, a red cross at zero, a second input for a big price. */
    @GameTest
    public void anOfferCarriesStockAndSplitsABigPrice(GameTestHelper helper) {
        StoreNPC.ShopEntry stocked = new StoreNPC.ShopEntry(Items.IRON_SWORD, null, "Iron Sword",
                MerchantThemes.EMERALD, 5, 3);
        MerchantOffer offer = StoreNPC.offerFor(stocked);
        helper.assertTrue(!offer.isOutOfStock(), "a line with stock is on sale");
        helper.assertValueEqual(offer.getMaxUses(), 3, "stock is the max uses");
        helper.assertTrue(offer.getResult().is(Items.IRON_SWORD), "the result is the real item");
        helper.assertValueEqual(offer.getCostA().getCount(), 5, "a small price is one input");
        helper.assertTrue(offer.getItemCostB().isEmpty(), "and has no second input");

        StoreNPC.ShopEntry gone = new StoreNPC.ShopEntry(Items.IRON_SWORD, null, "Iron Sword",
                MerchantThemes.EMERALD, 5, 0);
        helper.assertTrue(StoreNPC.offerFor(gone).isOutOfStock(), "a line with no stock shows the red cross");

        StoreNPC.ShopEntry big = new StoreNPC.ShopEntry(Items.IRON_SWORD, null, "Iron Sword",
                MerchantThemes.EMERALD, 70, 1);
        MerchantOffer bigOffer = StoreNPC.offerFor(big);
        helper.assertValueEqual(bigOffer.getCostA().getCount(), 64, "a price past a stack fills the first input");
        helper.assertValueEqual(bigOffer.getItemCostB().get().count(), 6, "and the rest goes in the second");
        helper.succeed();
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
