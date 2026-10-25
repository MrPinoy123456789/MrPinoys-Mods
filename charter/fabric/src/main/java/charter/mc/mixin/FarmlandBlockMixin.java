package charter.mc.mixin;

import charter.mc.land.Hazards;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mixin 10: trampling (decision D19). Landing on farmland fires no event. Only the
 * turn into dirt is held back, so fall damage still works. The class is
 * {@code FarmlandBlock} in 26.2, not {@code FarmBlock}.
 */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {

    @WrapWithCondition(method = "fallOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmlandBlock;turnToDirt(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private boolean charter$guardTrample(Entity entity, BlockState state, Level level, BlockPos pos) {
        return Hazards.mayTrample(level, pos, entity);
    }
}
