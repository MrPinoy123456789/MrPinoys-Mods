package pocketdungeons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.DungeonTools;
import pocketdungeons.PocketDungeonsMod;

/**
 * (M48) Records every block a player places inside the dungeon dimension, so
 * {@code RoomProtection}'s "right tool for the job" check can exempt them: a
 * player can always break what they placed, regardless of whether they are
 * holding the correct tool for it.
 *
 * <p>Injects at the return of {@link BlockItem#place}: if the placement
 * succeeded, the clicked position is added to the player's instance record's
 * {@code playerPlaced} set via {@link DungeonTools#recordPlayerPlacement}.
 * The set dies with the instance, so there is no cleanup beyond the natural
 * teardown.
 */
@Mixin(BlockItem.class)
public class BlockItemPlaceMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void pocketdungeons$recordPlacement(BlockPlaceContext context,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue() != InteractionResult.SUCCESS) {
            return;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return;
        }
        DungeonTools.recordPlayerPlacement(player.getUUID(), context.getClickedPos());
    }
}
