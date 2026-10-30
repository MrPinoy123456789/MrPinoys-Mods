package charter.mc.mixin;

import charter.mc.land.Hazards;
import charter.mc.land.Protection;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 4: fire. Ticking fire has no event. Fire cannot burn a block in, or spread
 * into, protected land of a different owner than the land the fire stands in.
 *
 * <p>{@code tick} places new fire (and ages the fire itself, which stays in place);
 * {@code checkBurnOut} eats a neighbouring block. Both are given the fire's own
 * position through the tick's argument.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    /** Spread: a new fire is only set where the fire's own owner could reach. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean charter$guardSpread(ServerLevel level, BlockPos target, BlockState state, int flags,
                                        Operation<Boolean> original, @Local(argsOnly = true) BlockPos fire) {
        if (Protection.crossesIntoGuarded(level, fire, target)) return false;
        return original.call(level, target, state, flags);
    }

    /** Spread in 26.3: the new fire is placed with setBlockAndUpdate, no longer setBlock with flags. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean charter$guardSpreadAndUpdate(ServerLevel level, BlockPos target, BlockState state,
                                                 Operation<Boolean> original, @Local(argsOnly = true) BlockPos fire) {
        if (Protection.crossesIntoGuarded(level, fire, target)) return false;
        return original.call(level, target, state);
    }

    /** Burn out: the neighbour is only eaten if the fire may reach it. The fire is the block that called, so its position is the tick's. */
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void charter$guardBurn(Level level, BlockPos target, int chance, RandomSource random, int age, CallbackInfo ci) {
        if (level.isClientSide()) return;
        BlockPos fire = Hazards.burning;
        if (fire != null && Protection.crossesIntoGuarded(level, fire, target)) ci.cancel();
    }

    /** Remembers which fire is ticking, so {@code checkBurnOut} knows where it burns from. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void charter$enterTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        Hazards.burning = pos;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void charter$leaveTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        Hazards.burning = null;
    }
}
