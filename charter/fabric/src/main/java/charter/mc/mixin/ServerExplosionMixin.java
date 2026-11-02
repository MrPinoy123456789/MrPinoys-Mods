package charter.mc.mixin;

import charter.mc.land.Hazards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin 2: explosions. They fire no break event, so the list of blocks an
 * explosion will destroy is filtered on its way out: protected land and the mod's
 * objects are removed, unless a permitted player lit it (see Hazards.explodable).
 * In the spawn, entities are neither hurt nor pushed.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Entity source;

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
    private void charter$spareClaims(CallbackInfoReturnable<List<BlockPos>> cir) {
        cir.setReturnValue(Hazards.explodable(level, source, cir.getReturnValue()));
    }

    /** The spawn: an explosion does not hurt what stands there, frames, carts and dropped items included. */
    @WrapOperation(method = "hurtEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean charter$spareSpawnEntities(Entity entity, ServerLevel at, DamageSource damage, float amount, Operation<Boolean> original) {
        if (!Hazards.explosionMayHurt(entity, source)) return false;
        return original.call(entity, at, damage, amount);
    }

    /** The spawn: nor does it fling them, so a cannon outside cannot throw visitors about. */
    @WrapWithCondition(method = "hurtEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;pushFromExplosion(Lnet/minecraft/world/phys/Vec3;)V"))
    private boolean charter$holdSpawnEntities(Entity entity, Vec3 push) {
        return Hazards.explosionMayHurt(entity, source);
    }
}
