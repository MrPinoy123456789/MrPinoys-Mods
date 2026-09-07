package pocketdungeons;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Listens for right-click use of the Room Editor Kit item and opens the
 * kit GUI. Registered alongside the other server-side listeners in
 * {@link PocketDungeonsMod#onInitialize}.
 *
 * <p>The kit item is an ender chest with custom NBT; right-clicking it in
 * the air (no target block) triggers {@code UseItemCallback}, which is the
 * same hook {@link SilenceListener} uses. Right-clicking a block with the
 * kit would trigger {@code UseBlockCallback} instead, which is owned by
 * {@link RitualListener}; to avoid conflict, the kit is used in the air.
 *
 * <p>See {@code docs/reference/ROOM_AUTHORING_SPEC.md} section 4.1-4.2.
 */
final class RoomEditorListener {

    private RoomEditorListener() {}

    static void register() {
        UseItemCallback.EVENT.register(RoomEditorListener::onUseItem);
    }

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!RoomEditorKit.isKitItem(stack)) {
            return InteractionResult.PASS;
        }
        // Only open the kit GUI if the player is in a build room.
        InstanceRecord record = InstanceRegistry.byMember.get(serverPlayer.getUUID());
        if (record == null || !record.adminBuild) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "The Room Editor Kit only works inside a build room."));
            return InteractionResult.FAIL;
        }
        RoomEditorKit.openKitGui(serverPlayer);
        return InteractionResult.SUCCESS_SERVER;
    }
}
