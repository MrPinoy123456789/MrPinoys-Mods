package pocketdungeons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.DungeonTools;
import pocketdungeons.PocketDungeonsMod;
import pocketdungeons.RoomEditorHistory;
import pocketdungeons.RoomEditorKit;

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
 *
 * <p>(M67 Room Editor) Also transfers the {@code pd_authored} flag from the
 * placed item's custom NBT to the placed block entity, so the stamp pipeline
 * can distinguish editor-placed trial spawners and vaults from stamp-placed
 * ones. See {@code docs/reference/ROOM_AUTHORING_SPEC.md} section 6.
 */
@Mixin(BlockItem.class)
public class BlockItemPlaceMixin {

    /**
     * Captures the block state at the clicked position before placement
     * begins, so the undo history can record an accurate "before" state even
     * for replaceable blocks (tall grass, snow layers, water, etc.). Uses a
     * ThreadLocal because BlockItem instances are shared singletons and
     * Minecraft's server logic is single-threaded per tick.
     */
    private static final ThreadLocal<BlockState> BEFORE_STATE = new ThreadLocal<>();

    @Inject(method = "place", at = @At("HEAD"))
    private void pocketdungeons$captureBefore(BlockPlaceContext context,
                                              CallbackInfoReturnable<InteractionResult> cir) {
        BEFORE_STATE.set(context.getLevel().getBlockState(context.getClickedPos()));
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void pocketdungeons$recordPlacement(BlockPlaceContext context,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        BlockState before = BEFORE_STATE.get();
        BEFORE_STATE.remove();
        if (cir.getReturnValue() != InteractionResult.SUCCESS) {
            return;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return;
        }
        BlockPos pos = context.getClickedPos();
        DungeonTools.recordPlayerPlacement(player.getUUID(), pos);

        // Record for undo history (build rooms only). The before-state was
        // captured at HEAD so replaceable blocks undo correctly.
        BlockState after = player.level().getBlockState(pos);
        RoomEditorHistory.record(player, pos,
                before != null ? before : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                after);

        // Transfer pd_authored from the item to the placed block entity.
        ItemStack stack = context.getItemInHand();
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.copyTag().getBooleanOr(RoomEditorKit.AUTHORED_KEY, false)) {
            return;
        }
        BlockEntity be = player.level().getBlockEntity(pos);
        if (be == null) {
            return;
        }
        CompoundTag beTag = be.saveWithoutMetadata(player.level().registryAccess());
        beTag.putBoolean(RoomEditorKit.AUTHORED_KEY, true);
        be.loadWithComponents(TagValueInput.create(
                ProblemReporter.DISCARDING, player.level().registryAccess(), beTag));
        be.setChanged();
    }
}
