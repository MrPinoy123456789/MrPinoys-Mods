package charter.mc.mixin;

import charter.mc.land.Hazards;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin 3: pistons. A push or pull fires no event. The move is refused when any
 * block it pushes, destroys or pushes into lies in another owner's protected land.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void charter$guardMove(Level level, BlockPos pos, Direction facing, boolean extending,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (!level.isClientSide() && !Hazards.pistonMayMove(level, pos, facing, extending)) {
            cir.setReturnValue(false);
        }
    }
}
