package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spawn rules: a real lightning bolt in the spawn goes out before it does anything, so it lights no fire, charges no
 * creeper and turns no villager into a witch. A visual-only bolt is left alone.
 */
@Mixin(LightningBolt.class)
public abstract class SpawnLightningMixin {

    @Shadow private boolean visualOnly;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void charter$noLightningInSpawn(CallbackInfo ci) {
        LightningBolt self = (LightningBolt) (Object) this;
        if (visualOnly || self.level().isClientSide()) return;
        if (SpawnRules.refusesLightning(self)) {
            self.discard();
            ci.cancel();
        }
    }
}
