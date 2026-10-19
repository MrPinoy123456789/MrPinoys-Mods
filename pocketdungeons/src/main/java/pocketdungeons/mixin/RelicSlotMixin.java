package pocketdungeons.mixin;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ShulkerBoxSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.RelicObjective;

/**
 * (Campaign and endgame T7) The relic will not fit: no slot of any container takes it, whether by click, drag,
 * shift click or number key (every one of those asks {@link Slot#mayPlace}). Only the player's own inventory
 * slots (hotbar, pack, armour and offhand) accept it. The shulker box slot overrides {@code mayPlace} with its
 * own rule, so it is listed too. The message is {@link RelicMenuMixin}'s.
 */
@Mixin({Slot.class, ShulkerBoxSlot.class})
public class RelicSlotMixin {

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void pocketdungeons$relicWillNotFit(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.isEmpty() || !RelicObjective.isRelic(stack)) {
            return;
        }
        if (!(((Slot) (Object) this).container instanceof Inventory)) {
            cir.setReturnValue(false);
        }
    }
}
