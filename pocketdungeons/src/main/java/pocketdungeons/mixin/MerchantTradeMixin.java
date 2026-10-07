package pocketdungeons.mixin;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pocketdungeons.TradeJournal;

/**
 * J4: journals completed trades on the mod's merchant villagers. Vanilla's
 * {@code MerchantResultSlot.onTake} calls {@code merchant.notifyTrade(offer)}
 * once per completed trade; on the server that merchant is the real
 * {@link AbstractVillager}, so this inject runs exactly once per trade,
 * server side only. Purchase versus sale, prices and the vendor name are
 * resolved in {@link TradeJournal}, which also filters to tagged merchants
 * so ordinary villagers are untouched.
 */
@Mixin(AbstractVillager.class)
public abstract class MerchantTradeMixin {

    @Inject(method = "notifyTrade", at = @At("HEAD"))
    private void pocketdungeons$journalTrade(MerchantOffer offer, CallbackInfo ci) {
        TradeJournal.onTrade((AbstractVillager) (Object) this, offer);
    }
}
