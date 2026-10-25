package charter.gametest.mixin;

import net.minecraft.world.level.material.FlowingFluid;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** M0 spike: protection Mixin 6 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(FlowingFluid.class)
public abstract class SpikeFlowingFluidMixin implements SpikeMarker {
    @Inject(method = "spreadTo", at = @At("HEAD"))
    private void charter$spike(CallbackInfo ci) {
    }
}
