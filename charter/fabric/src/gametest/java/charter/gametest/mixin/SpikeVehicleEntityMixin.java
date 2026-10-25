package charter.gametest.mixin;

import net.minecraft.world.entity.vehicle.VehicleEntity;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** M0 spike: protection Mixin 9 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(VehicleEntity.class)
public abstract class SpikeVehicleEntityMixin implements SpikeMarker {
    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void charter$spike(CallbackInfoReturnable<?> ci) {
    }
}
