package foundry.mixin;

import foundry.mc.Wings;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A race that glides (the Fairies) can glide with nothing in the chest slot: the same conditions as vanilla (off the
 * ground, not riding, no levitation), with the race in place of an elytra. Server side only.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityGlide {
    @Inject(method = "canGlide", at = @At("HEAD"), cancellable = true)
    private void foundry$raceGlides(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof net.minecraft.server.level.ServerPlayer player && Wings.isWinged(player.getUUID())
                && !self.onGround() && !self.isPassenger() && !self.hasEffect(MobEffects.LEVITATION)) {
            cir.setReturnValue(true);
        }
    }
}
