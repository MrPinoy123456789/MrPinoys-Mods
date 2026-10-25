package charter.gametest.mixin;

import net.minecraft.world.level.block.DispenserBlock;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** M0 spike: protection Mixin 7 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(DispenserBlock.class)
public abstract class SpikeDispenserBlockMixin implements SpikeMarker {
    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void charter$spike(CallbackInfo ci) {
    }
}
