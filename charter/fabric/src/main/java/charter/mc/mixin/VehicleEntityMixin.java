package charter.mc.mixin;

import charter.mc.land.Hazards;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin 9: minecarts and boats being hurt. The attack event covers melee only;
 * arrows, tridents and fire charges break a cart through {@code hurtServer}. In
 * protected land only a permitted player can do it (acceptance test K4).
 */
@Mixin(VehicleEntity.class)
public abstract class VehicleEntityMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void charter$guardHurt(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (!Hazards.vehicleMayBeHurt(level, self.blockPosition(), source)) cir.setReturnValue(false);
    }
}
