package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spawn rules: paintings, item frames and leash knots in the spawn are not broken by an arrow, a trident or an explosion
 * from outside. They are not living, so the damage event does not see them; item frames hurt in their own method.
 */
@Mixin({BlockAttachedEntity.class, ItemFrame.class})
public abstract class SpawnHangingEntityMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void charter$spareInSpawn(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        if (SpawnRules.sparesThing((Entity) (Object) this, source)) cir.setReturnValue(false);
    }
}
