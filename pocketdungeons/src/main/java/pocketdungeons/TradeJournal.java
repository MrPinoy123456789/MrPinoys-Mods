package pocketdungeons;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * J4: the journal seam for vanilla villager trades. Called from
 * {@code MerchantTradeMixin} at the head of {@code AbstractVillager#notifyTrade},
 * which {@code MerchantResultSlot.onTake} fires once per completed trade on the
 * server merchant (verified in the 26.2 jar). Only our tagged merchants are
 * journaled: the store keeper ({@link StoreNPC#STORE_TAG}) and the home vendor
 * ({@link LibrarianNPC#LIBRARIAN_TAG}).
 *
 * <p>Public because mixins live in the {@code pocketdungeons.mixin} package and
 * cannot reach package private types, the same reason {@link DungeonTools} is
 * public.
 *
 * <p>An emerald result means the player sold drops for emeralds
 * ({@code shop_sale}); anything else is a purchase paid in emeralds
 * ({@code shop_purchase}).
 */
public final class TradeJournal {

    private TradeJournal() {}

    /** Records one completed trade on a tagged merchant. No-ops for anyone else. */
    public static void onTrade(AbstractVillager merchant, MerchantOffer offer) {
        if (!(merchant.getTradingPlayer() instanceof ServerPlayer player)) {
            return;
        }
        boolean store = merchant.entityTags().contains(StoreNPC.STORE_TAG);
        boolean librarian = merchant.entityTags().contains(LibrarianNPC.LIBRARIAN_TAG);
        if (!store && !librarian) {
            return;
        }
        String vendor = merchant.getName().getString();
        ItemStack result = offer.getResult();
        ItemStack cost = offer.getBaseCostA();
        if (result.is(Items.EMERALD)) {
            PlaytestJournal.shopSale(player, cost.getItem(), cost.getCount(), result.getCount(), vendor);
        } else {
            PlaytestJournal.shopPurchase(player, result.getItem(), result.getHoverName().getString(),
                    cost.is(Items.EMERALD) ? cost.getCount() : 0, Items.EMERALD, vendor);
        }
        if (librarian) {
            StationTutorial.used(player, StationTutorial.Step.VENDOR);
        }
    }
}
