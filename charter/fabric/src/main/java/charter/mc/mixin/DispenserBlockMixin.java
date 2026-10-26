package charter.mc.mixin;

import charter.mc.land.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 7: dispensers. They fire no event, and a dispenser outside a border can
 * place lava or fire into a claim. It refuses to dispense into protected land that
 * belongs to someone other than the owner of the dispenser's own chunk.
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void charter$guardDispense(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        BlockPos target = pos.relative(state.getValue(DispenserBlock.FACING));
        if (Protection.crossesIntoGuarded(level, pos, target)) ci.cancel();
    }
}
