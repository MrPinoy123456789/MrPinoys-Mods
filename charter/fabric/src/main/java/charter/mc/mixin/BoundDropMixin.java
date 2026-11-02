package charter.mc.mixin;

import charter.mc.book.BoundItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A lifted Nexus cannot be dropped (the Q key, or thrown out of the inventory screen): it goes back into the inventory. */
@Mixin(ServerPlayer.class)
public abstract class BoundDropMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void charter$liftedNexusStays(ItemStack stack, boolean thrown, Prediction prediction, CallbackInfoReturnable<ItemEntity> cir) {
        if (BoundItems.refusesDrop((ServerPlayer) (Object) this, stack)) cir.setReturnValue(null);
    }
}
