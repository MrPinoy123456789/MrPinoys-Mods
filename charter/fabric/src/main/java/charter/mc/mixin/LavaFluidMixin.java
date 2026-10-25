package charter.mc.mixin;

import charter.mc.land.Protection;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 5: lava. Its random tick lights fires nearby and has no event. Lava cannot
 * set a fire inside protected land of another owner. The water-meets-lava stone
 * made in {@code spreadTo} is held to the same border.
 */
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {

    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean charter$guardIgnition(ServerLevel level, BlockPos target, BlockState state,
                                          Operation<Boolean> original, @Local(argsOnly = true) BlockPos lava) {
        if (Protection.crossesIntoGuarded(level, lava, target)) return false;
        return original.call(level, target, state);
    }

    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void charter$guardStone(LevelAccessor accessor, BlockPos target, BlockState state, Direction direction,
                                    FluidState fluid, CallbackInfo ci) {
        if (accessor instanceof Level level && Protection.crossesIntoGuarded(level, target.relative(direction.getOpposite()), target)) {
            ci.cancel();
        }
    }
}
