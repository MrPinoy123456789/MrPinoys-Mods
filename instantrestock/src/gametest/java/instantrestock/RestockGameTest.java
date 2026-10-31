package instantrestock;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

public final class RestockGameTest {

    private static Villager villagerWithOneTrade(GameTestHelper helper) {
        Villager villager = EntityTypes.VILLAGER.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        MerchantOffers offers = villager.getOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD), 12, 2, 0.05F));
        return villager;
    }

    @GameTest
    public void aUsedTradeComesBackAtOnce(GameTestHelper helper) {
        Villager villager = villagerWithOneTrade(helper);
        MerchantOffer offer = villager.getOffers().get(0);
        offer.increaseUses();
        offer.increaseUses();
        helper.assertTrue(offer.getUses() == 2, "the trade has been used twice");
        villager.notifyTrade(offer);
        helper.assertTrue(offer.getUses() == 0, "the trade is back after the trade is noted: " + offer.getUses());
        helper.succeed();
    }

    @GameTest
    public void pricesAndDemandAreLeftAlone(GameTestHelper helper) {
        Villager villager = villagerWithOneTrade(helper);
        MerchantOffer offer = villager.getOffers().get(0);
        int demand = offer.getDemand();
        offer.increaseUses();
        villager.notifyTrade(offer);
        helper.assertTrue(offer.getDemand() == demand, "restocking at once does not raise the demand");
        helper.succeed();
    }
}
