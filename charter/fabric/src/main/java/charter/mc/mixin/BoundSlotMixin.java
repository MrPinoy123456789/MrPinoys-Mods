package charter.mc.mixin;

import charter.mc.book.BoundItems;
import net.minecraft.world.inventory.ShulkerBoxSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Charter book and a lifted Nexus go only into their holder's own inventory: every other slot (chests, barrels,
 * ender chests, hoppers, furnaces, crafting grids, horse packs) refuses them, by a click or a shift-click alike. The
 * shulker box slot answers without asking {@link Slot}, so it is named here too.
 */
@Mixin({Slot.class, ShulkerBoxSlot.class})
public abstract class BoundSlotMixin {

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void charter$boundStaysWithYou(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (BoundItems.refusesSlot(((Slot) (Object) this).container, stack)) cir.setReturnValue(false);
    }
}
