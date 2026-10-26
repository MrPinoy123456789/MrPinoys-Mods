package charter.mc.mixin;

import charter.mc.land.HopperGate;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin 8: the hopper and dropper insert. Container transfers fire no event. This
 * is the gate only: every insert is offered to {@link HopperGate}, which has no
 * interceptor until the granary (M9) and supply lines (M10) register theirs.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true)
    private static void charter$gate(Container source, Container destination, ItemStack stack, Direction face,
                                     CallbackInfoReturnable<ItemStack> cir) {
        ItemStack answer = HopperGate.offer(source, destination, stack, face);
        if (answer != null) cir.setReturnValue(answer);
    }
}
