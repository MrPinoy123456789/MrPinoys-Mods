package charter.mc.mixin;

import charter.mc.book.BoundItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Charter book and a lifted Nexus never go into a bundle, which could carry them to another player. */
@Mixin(BundleContents.class)
public abstract class BoundBundleMixin {

    @Inject(method = "canItemBeInBundle", at = @At("HEAD"), cancellable = true)
    private static void charter$boundStaysOut(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (BoundItems.isBound(stack)) cir.setReturnValue(false);
    }
}
