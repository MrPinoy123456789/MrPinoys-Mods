package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spawn rules: the ground keeps its shape. A random tick (crops, saplings, grass, leaves, ice, snow, farmland, vines)
 * does nothing inside the spawn, and a block hit by a projectile there does not react (TNT, pots, targets, chorus).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class SpawnBlockStateMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void charter$freezeSpawn(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (SpawnRules.freezes(level, pos)) ci.cancel();
    }

    @Inject(method = "onProjectileHit", at = @At("HEAD"), cancellable = true)
    private void charter$ignoreProjectilesInSpawn(Level level, BlockState state, BlockHitResult hit, Projectile projectile, CallbackInfo ci) {
        if (!level.isClientSide() && SpawnRules.refusesProjectileHit(level, hit.getBlockPos(), projectile)) ci.cancel();
    }
}
