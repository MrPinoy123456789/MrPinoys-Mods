package charter.gametest.mixin;

import net.minecraft.world.item.BlockItem;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** M0 spike: protection Mixin 1 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(BlockItem.class)
public abstract class SpikeBlockItemMixin implements SpikeMarker {
    @Inject(method = "place", at = @At("HEAD"))
    private void charter$spike(CallbackInfoReturnable<?> ci) {
    }
}
