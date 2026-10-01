package pocketdungeons.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pocketdungeons.DungeonTools;
import pocketdungeons.PocketDungeonsMod;

/**
 * PD-89: caps a crafting result's durability on every craft path, not only
 * the click-to-take one {@link ResultSlotMixin} sees. Shift-clicking a result
 * in a crafting table runs {@code CraftingMenu.quickMoveStack}, which moves
 * the result stack straight into the inventory without calling
 * {@code ResultSlot.remove}; before it moves the stack it calls
 * {@link Item#onCraftedBy} on it, so the cap is applied here, in place. A
 * crafted diamond sword kept its vanilla 1561 through that gap while dropped
 * gear burned out in a floor (playtest 2026-09-29-3).
 *
 * <p>Same scope as {@link ResultSlotMixin}: server players in the dungeon
 * dimension only, and only items {@link DungeonTools#durabilityCap} limits.
 * Applying it on the click-to-take path too is harmless: an already-capped
 * stack is left alone.
 */
@Mixin(Item.class)
public class CraftedDurabilityMixin {

    @Inject(method = "onCraftedBy", at = @At("HEAD"))
    private void pocketdungeons$capCraftedDurability(ItemStack stack, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer
                && serverPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            DungeonTools.capInPlace(stack);
        }
    }
}
