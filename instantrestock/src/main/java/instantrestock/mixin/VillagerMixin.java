package instantrestock.mixin;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A villager's trades come back the moment one is used. Every offer is reset, but prices and demand are left alone, so a
 * villager does not get dearer for being traded with. It runs on the server, so Java and Bedrock players (through Geyser)
 * see the same thing. {@code notifyTrade} lives on {@link AbstractVillager}; wandering traders are left alone.
 */
@Mixin(AbstractVillager.class)
public abstract class VillagerMixin {

    @Inject(method = "notifyTrade", at = @At("TAIL"))
    private void instantrestock$restockAtOnce(MerchantOffer offer, CallbackInfo ci) {
        if (!((Object) this instanceof Villager villager)) return;
        for (MerchantOffer each : villager.getOffers()) each.resetUses();
    }
}
