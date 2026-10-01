package pocketdungeons.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.DungeonTools;
import pocketdungeons.PocketDungeonsMod;

/**
 * (M48) Caps the durability of mining tools crafted inside the dungeon
 * dimension. A stone pickaxe crafted in a dungeon crafting table gets
 * {@code max_damage} 12 instead of vanilla 131, so it is a starter tool that
 * clears a couple of cells, not a permanent mining operation that trivializes
 * the scarcity the dungeon is built around.
 *
 * <p>Injects at the return of {@link ResultSlot#remove}: the item has already
 * been lifted out of the result container, so modifying the returned stack
 * changes what lands in the player's cursor or inventory without affecting the
 * result slot's own state. This catches the click-to-take path. It does not
 * catch shift-click: {@code CraftingMenu.quickMoveStack} moves the result
 * stack without calling {@code remove} (PD-89), which
 * {@link CraftedDurabilityMixin} covers.
 *
 * <p>Only fires for {@code ServerPlayer}s inside the dungeon dimension. A
 * player crafting in the overworld gets vanilla durability, so the cap does
 * not leak out of the dungeon.
 */
@Mixin(ResultSlot.class)
public class ResultSlotMixin {

    @Shadow @Final private Player player;

    @Inject(method = "remove", at = @At("RETURN"), cancellable = true)
    private void pocketdungeons$capToolDurability(int count,
                                                  CallbackInfoReturnable<ItemStack> cir) {
        ItemStack original = cir.getReturnValue();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!serverPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return;
        }
        if (DungeonTools.durabilityCap(original.getItem()) <= 0) {
            return;
        }
        cir.setReturnValue(DungeonTools.limitDurability(original));
    }
}
