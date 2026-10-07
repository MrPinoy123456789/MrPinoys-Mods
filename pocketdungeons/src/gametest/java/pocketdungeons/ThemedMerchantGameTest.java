package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Themed store merchants (J4): the merchant follows the floor's theme, sells
 * for emeralds through plain vanilla {@link MerchantOffer}s, and buys the
 * floor's surplus drops back for emeralds. A theme without a merchant keeps
 * the original emerald-only shopkeeper. Reads the villager's own offer list,
 * the same data the vanilla trading screen shows, rather than opening a
 * screen (nothing headless clicks a merchant menu).
 */
@SuppressWarnings("removal")
public final class ThemedMerchantGameTest {

    @GameTest
    public void anOssuaryFloorHostsABoneCollectorThatBuysBones(GameTestHelper helper) {
        for (long seed = 1; seed <= 12; seed++) {
            Villager villager = spawnStore(helper, "pocketdungeons:ossuary", seed);
            helper.assertValueEqual(villager.getName().getString(), "Bone Collector", "merchant for ossuary");
            helper.assertTrue(!offers(villager).isEmpty(), "the merchant has offers");
            for (MerchantOffer offer : offers(villager)) {
                helper.assertTrue(offer.getBaseCostA().is(Items.EMERALD)
                                || offer.getResult().is(Items.EMERALD),
                        "every line is an emerald sell or an emerald buy, saw " + offer);
            }
            helper.assertTrue(buys(villager, Items.BONE), "the ossuary merchant buys bones for emeralds");
            villager.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void aNetherFloorHostsANetherMerchantThatBuysBlazePowderAndMagmaCream(GameTestHelper helper) {
        for (long seed = 1; seed <= 12; seed++) {
            Villager villager = spawnStore(helper, "basalt_foundry", seed);
            helper.assertValueEqual(villager.getName().getString(), "Powder Trader",
                    "merchant for basalt foundry");
            helper.assertTrue(buys(villager, Items.BLAZE_POWDER), "it buys blaze powder");
            helper.assertTrue(buys(villager, Items.MAGMA_CREAM), "it buys magma cream");
            villager.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void aThemeWithoutAMerchantKeepsTheEmeraldShopkeeper(GameTestHelper helper) {
        for (String theme : new String[] {null, "pocketdungeons:prismarine", "somepack:unheard_of"}) {
            Villager villager = spawnStore(helper, theme, 5);
            helper.assertValueEqual(villager.getName().getString(), "Wandering Merchant", "default merchant");
            for (MerchantOffer offer : offers(villager)) {
                helper.assertTrue(offer.getBaseCostA().is(Items.EMERALD),
                        "the default merchant sells for emeralds only, saw " + offer);
                helper.assertTrue(!offer.getResult().is(Items.EMERALD),
                        "and buys nothing, saw " + offer);
            }
            villager.discard();
        }
        helper.succeed();
    }

    /** A merchant never sells the drop it buys with, and never lists an item twice. */
    @GameTest
    public void aStoreNeverSellsItsOwnBuyDropOrRepeatsAnItem(GameTestHelper helper) {
        for (long seed = 1; seed <= 20; seed++) {
            Villager villager = spawnStore(helper, "ender_archive", seed);
            List<String> sold = new ArrayList<>();
            int sellLines = 0;
            for (MerchantOffer offer : offers(villager)) {
                if (!offer.getBaseCostA().is(Items.EMERALD)) {
                    continue;
                }
                sellLines++;
                String item = BuiltInRegistries.ITEM.getKey(offer.getResult().getItem()).toString();
                helper.assertTrue(!item.equals("minecraft:end_stone"),
                        "the end broker buys end stone, so it does not sell it");
                helper.assertTrue(!sold.contains(item), "no item listed twice, saw " + item + " again");
                sold.add(item);
                helper.assertTrue(offer.getMaxUses() >= 1 && offer.getXp() == 0,
                        "stock is the offer's maxUses and trades pay no xp");
            }
            helper.assertTrue(sellLines >= 4 && sellLines <= StorePricing.MAX_SLOTS,
                    "a store lists four to seven sell lines, saw " + sellLines);
            villager.discard();
        }
        helper.succeed();
    }

    /**
     * J4's journal seam: a completed trade on a tagged merchant writes a
     * {@code shop_purchase} line through {@code MerchantTradeMixin}.
     */
    @GameTest
    public void aCompletedTradeIsJournaled(GameTestHelper helper) {
        Villager villager = spawnStore(helper, "deepslate", 7);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MerchantOffer sell = null;
        for (MerchantOffer offer : offers(villager)) {
            if (offer.getBaseCostA().is(Items.EMERALD)) {
                sell = offer;
                break;
            }
        }
        helper.assertTrue(sell != null, "the merchant sells something for emeralds");
        villager.setTradingPlayer(player);
        villager.notifyTrade(sell);
        villager.setTradingPlayer(null);

        boolean journaled = false;
        for (com.google.gson.JsonObject line : PlaytestJournal.recent(player.getUUID(), 50)) {
            if (line.has("ev") && "shop_purchase".equals(line.get("ev").getAsString())) {
                journaled = line.get("vendor").getAsString().equals(villager.getName().getString())
                        && line.get("price").getAsInt() == sell.getBaseCostA().getCount();
            }
        }
        helper.assertTrue(journaled, "a shop_purchase line names the vendor and the price");
        villager.discard();
        helper.succeed();
    }

    private static MerchantOffers offers(Villager villager) {
        return villager.getOffers();
    }

    /** Whether the villager carries a buy offer for {@code drop} paying emeralds. */
    private static boolean buys(Villager villager, net.minecraft.world.item.Item drop) {
        for (MerchantOffer offer : offers(villager)) {
            if (offer.getBaseCostA().is(drop) && offer.getResult().is(Items.EMERALD)) {
                return true;
            }
        }
        return false;
    }

    private static Villager spawnStore(GameTestHelper helper, String theme, long seed) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        StoreNPC.spawn(level, origin, seed, theme);
        List<Villager> found = level.getEntitiesOfClass(Villager.class,
                new AABB(origin).inflate(RoomGeometry.CELL),
                v -> v.entityTags().contains(StoreNPC.STORE_TAG));
        if (found.isEmpty()) {
            helper.fail("StoreNPC.spawn left no store villager for theme " + theme);
        }
        return found.get(0);
    }
}
