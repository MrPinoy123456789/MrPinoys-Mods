package pocketdungeons.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pocketdungeons.RelicObjective;

/**
 * (Campaign and endgame T7) Says {@link RelicObjective#WONT_FIT} when a click tries to put the relic into a
 * container. The refusal itself is {@link RelicSlotMixin}'s ({@code Slot.mayPlace}); this only speaks, and never
 * changes the click.
 */
@Mixin(AbstractContainerMenu.class)
public class RelicMenuMixin {

    @Inject(method = "clicked", at = @At("HEAD"))
    private void pocketdungeons$sayWontFit(int slotIndex, int button, ContainerInput input, Player player,
                                           CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (menu instanceof InventoryMenu || slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return;
        }
        Slot slot = menu.slots.get(slotIndex);
        boolean ownSlot = slot.container instanceof Inventory;
        boolean refused;
        if (input == ContainerInput.QUICK_MOVE) {
            refused = ownSlot && RelicObjective.isRelic(slot.getItem());
        } else if (input == ContainerInput.SWAP) {
            refused = !ownSlot && button >= 0 && button < 9
                    && RelicObjective.isRelic(player.getInventory().getItem(button));
        } else if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_CRAFT) {
            refused = !ownSlot && RelicObjective.isRelic(menu.getCarried());
        } else {
            refused = false;
        }
        if (refused) {
            serverPlayer.sendOverlayMessage(Component.literal(RelicObjective.WONT_FIT).withStyle(ChatFormatting.YELLOW));
        }
    }
}
