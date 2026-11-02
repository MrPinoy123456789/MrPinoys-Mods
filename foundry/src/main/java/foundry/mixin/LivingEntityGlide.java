package foundry.mixin;

import foundry.mc.Wings;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A race that glides (the Fairies) can glide with nothing in the chest slot: the same conditions as vanilla (off the
 * ground, not riding, no levitation), with the race in place of an elytra. Server side only.
 *
 * <p>While gliding, vanilla's updateFallFlying wears down a glider every second: it picks one at random from the
 * equipped slots holding one, and with none (a Fairy gliding by race) that pick throws ("Bound must be positive") and
 * the player is kicked. So for a Fairy with no real glider equipped, the glide tick is done here instead: the same fall
 * distance check, the same stop when gliding is no longer allowed, the same glide game event, and no item touched.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityGlide {
    @Shadow protected int fallFlyTicks;

    @Shadow
    protected abstract boolean canGlide();

    @Inject(method = "canGlide", at = @At("HEAD"), cancellable = true)
    private void foundry$raceGlides(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof net.minecraft.server.level.ServerPlayer player && Wings.isWinged(player.getUUID())
                && !self.onGround() && !self.isPassenger() && !self.hasEffect(MobEffects.LEVITATION)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "updateFallFlying", at = @At("HEAD"), cancellable = true)
    private void foundry$raceGlideTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!Wings.glidesByRaceOnly(self)) return;
        ci.cancel();
        self.checkFallDistanceAccumulation();
        if (!canGlide()) {
            self.stopFallFlying();
            return;
        }
        int ticks = fallFlyTicks + 1;
        if (ticks % 10 == 0 && (ticks / 10) % 2 == 0) self.gameEvent(GameEvent.ELYTRA_GLIDE);
    }
}
