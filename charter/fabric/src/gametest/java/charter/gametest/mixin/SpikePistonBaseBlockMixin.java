package charter.gametest.mixin;

import net.minecraft.world.level.block.piston.PistonBaseBlock;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** M0 spike: protection Mixin 3 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(PistonBaseBlock.class)
public abstract class SpikePistonBaseBlockMixin implements SpikeMarker {
    @Inject(method = "moveBlocks", at = @At("HEAD"))
    private void charter$spike(CallbackInfoReturnable<?> ci) {
    }
}
