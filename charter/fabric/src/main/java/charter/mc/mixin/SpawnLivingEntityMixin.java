package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Spawn rules on players, where Fabric API has no event: a player who dies in the spawn drops nothing (the items come
 * back on respawn through {@code COPY_FROM}); a harmful effect from someone else does not land on a player there; and a
 * chorus fruit teleport from outside that lands inside is undone.
 */
@Mixin(LivingEntity.class)
public abstract class SpawnLivingEntityMixin {

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void charter$keepLootInSpawn(ServerLevel level, DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (SpawnRules.keepsDeathLoot(self) || charter.mc.RacePassives.keepsInventory(self)) ci.cancel();
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void charter$noHarmInSpawn(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {
        if (SpawnRules.refusesEffect((LivingEntity) (Object) this, effect, source)) cir.setReturnValue(false);
    }

    @Inject(method = "randomTeleport(DDDZLjava/util/function/Predicate;)Z", at = @At("HEAD"))
    private void charter$teleportStarts(double x, double y, double z, boolean particles, Predicate<BlockState> ok,
                                        CallbackInfoReturnable<Boolean> cir) {
        SpawnRules.teleportStarts((LivingEntity) (Object) this);
    }

    @Inject(method = "randomTeleport(DDDZLjava/util/function/Predicate;)Z", at = @At("RETURN"), cancellable = true)
    private void charter$teleportEnds(double x, double y, double z, boolean particles, Predicate<BlockState> ok,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (SpawnRules.teleportEnds((LivingEntity) (Object) this, cir.getReturnValueZ())) cir.setReturnValue(false);
    }
}
