package pocketdungeons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PD-195: fire does not spread in the dungeon dimension. A fire block there never burns a neighbour or lights
 * another one; it flickers for a few seconds and goes out. Rooms are built from burnable woods (the Kennels in
 * spruce) and a spark from a burning mob, a lava drip or a fire charge used to eat the whole build. Fire as a
 * hazard and burning mobs are untouched (damage and the Restless counterplay do not need the fire block to
 * spread); the rest of the world keeps vanilla fire.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockNoSpreadMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pocketdungeons$fireStaysPut(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                             CallbackInfo ci) {
        if (!pocketdungeons.DungeonWorldRules.applies(level)) {
            return;
        }
        ci.cancel();
        if (random.nextInt(3) == 0) {
            level.removeBlock(pos, false);
        } else {
            level.scheduleTick(pos, state.getBlock(), 20 + random.nextInt(20));
        }
    }
}
