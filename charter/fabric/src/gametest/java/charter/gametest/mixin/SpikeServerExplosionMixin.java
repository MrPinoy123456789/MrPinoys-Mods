package charter.gametest.mixin;

import net.minecraft.world.level.ServerExplosion;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** M0 spike: protection Mixin 2 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(ServerExplosion.class)
public abstract class SpikeServerExplosionMixin implements SpikeMarker {
    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
    private void charter$spike(CallbackInfoReturnable<?> ci) {
    }
}
