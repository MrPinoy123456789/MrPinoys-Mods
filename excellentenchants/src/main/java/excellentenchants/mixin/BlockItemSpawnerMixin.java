package excellentenchants.mixin;

import excellentenchants.SpawnerPlacing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets a player who is not an operator place a Silk Spawner spawner that keeps its mob (see SpawnerPlacing). */
@Mixin(BlockItem.class)
abstract class BlockItemSpawnerMixin {

    @Inject(method = "updateCustomBlockEntityTag(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private static void excellentenchants$spawnerMob(Level level, Player player, BlockPos pos, ItemStack stack,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (SpawnerPlacing.restore(level, player, pos, stack)) cir.setReturnValue(true);
    }
}
