package charter.gametest.mixin;

import net.minecraft.world.level.material.LavaFluid;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** M0 spike: protection Mixin 5 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(LavaFluid.class)
public abstract class SpikeLavaFluidMixin implements SpikeMarker {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void charter$spike(CallbackInfo ci) {
    }
}
