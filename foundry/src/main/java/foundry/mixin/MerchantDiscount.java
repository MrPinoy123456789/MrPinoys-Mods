package foundry.mixin;

import foundry.mc.RaceTraitsRuntime;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A race's better trade deals (the Humans): when a villager or wandering trader starts trading with a player of the
 * race, each offer's price is lowered by the race's {@code trade_discount} share, on the server before the offers are
 * sent, so Bedrock players see it too. It is the same per offer price change vanilla uses for Hero of the Village, with
 * no effect or particles, and a price never goes below one (vanilla's own floor). It comes off when trading stops.
 */
@Mixin(AbstractVillager.class)
abstract class MerchantDiscount {
    @Inject(method = "setTradingPlayer", at = @At("TAIL"))
    private void foundry$raceDiscount(Player player, CallbackInfo ci) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            RaceTraitsRuntime.discount((AbstractVillager) (Object) this, sp);
        }
    }

    @Inject(method = "stopTrading", at = @At("HEAD"))
    private void foundry$endDiscount(CallbackInfo ci) {
        RaceTraitsRuntime.undiscount((AbstractVillager) (Object) this);
    }
}
