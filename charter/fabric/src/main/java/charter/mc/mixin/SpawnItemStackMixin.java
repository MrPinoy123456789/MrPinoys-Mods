package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spawn rules: an item's own use on a block in the spawn (an axe stripping, a hoe tilling, bone meal, flint and steel, an
 * item frame, a spawn egg, an eye of ender) is refused for everyone but operators. It matters when a door or button on the
 * allowlist let the click through and its own use did nothing. Placing blocks has its own guard in {@code BlockItemMixin}.
 */
@Mixin(ItemStack.class)
public abstract class SpawnItemStackMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void charter$noItemUseInSpawn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (SpawnRules.refusesItemUseOn(context)) cir.setReturnValue(InteractionResult.FAIL);
    }
}
