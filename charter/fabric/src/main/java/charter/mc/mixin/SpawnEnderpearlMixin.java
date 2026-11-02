package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spawn rules: an ender pearl thrown from outside the spawn that lands inside it is lost and moves nobody. */
@Mixin(ThrownEnderpearl.class)
public abstract class SpawnEnderpearlMixin {

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void charter$noPearlIntoSpawn(HitResult hit, CallbackInfo ci) {
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        if (self.level().isClientSide()) return;
        if (SpawnRules.refusesPearl(self, self.getOwner(), hit.getLocation())) {
            self.discard();
            ci.cancel();
        }
    }
}
