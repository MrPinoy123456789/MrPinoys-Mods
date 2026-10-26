package charter.mc.mixin;

import charter.mc.land.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 6: flowing fluids. Flow has no event. Water and lava do not flow across a
 * border into protected land of another owner. In 26.2 the method is
 * {@code spreadTo} (it was {@code canSpreadTo}); it names the target block and the
 * flow direction, so the source is one step back.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void charter$guardFlow(LevelAccessor accessor, BlockPos target, BlockState state, Direction direction,
                                   FluidState fluid, CallbackInfo ci) {
        if (accessor instanceof Level level && Protection.crossesIntoGuarded(level, target.relative(direction.getOpposite()), target)) {
            ci.cancel();
        }
    }
}
