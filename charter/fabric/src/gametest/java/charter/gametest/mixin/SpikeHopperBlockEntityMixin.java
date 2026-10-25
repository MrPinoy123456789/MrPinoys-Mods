package charter.gametest.mixin;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import charter.gametest.SpikeMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** M0 spike: protection Mixin 8 of PLAN.md §5.6 will inject here. No-op. */
@Mixin(HopperBlockEntity.class)
public abstract class SpikeHopperBlockEntityMixin implements SpikeMarker {
    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private static void charter$spike(CallbackInfoReturnable<?> ci) {
    }
}
