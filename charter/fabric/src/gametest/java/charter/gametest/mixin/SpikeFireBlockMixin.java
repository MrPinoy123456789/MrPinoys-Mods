package charter.gametest.mixin;

import net.minecraft.world.level.block.FireBlock;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** M0 spike: protection Mixin 4 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(FireBlock.class)
public abstract class SpikeFireBlockMixin implements SpikeMarker {
    @Inject(method = "checkBurnOut", at = @At("HEAD"))
    private void charter$spike(CallbackInfo ci) {
    }
}
