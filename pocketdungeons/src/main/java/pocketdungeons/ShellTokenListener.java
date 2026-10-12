package pocketdungeons;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * PD-206: a shell token claims itself. Right-clicking with it grants the
 * palette directly, without the Home > Manage Room > Change Shell trip the
 * player could not find. The picker's Unlock button stays for anyone who does
 * reach it, and consumes the held stack exactly as this does.
 *
 * <p>A separate listener rather than a {@link RitualListener} branch for the
 * same reason {@link SilenceListener} stands alone: this is
 * {@code UseItemCallback} (item use with no target block), a different hook
 * answering a different question.
 */
final class ShellTokenListener {

    private ShellTokenListener() {}

    static void register() {
        UseItemCallback.EVENT.register(ShellTokenListener::onUseItem);
    }

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        return claim(serverPlayer);
    }

    /**
     * The claim itself, separate from the event guards so
     * {@code ShellTokenGameTest} can drive it. Consumes the held token and
     * adds its palette to the player's permanent set.
     */
    static InteractionResult claim(ServerPlayer serverPlayer) {
        ItemStack held = serverPlayer.getMainHandItem();
        String shellName = RoomBuilder.shellUnlockOf(held);
        if (shellName.isEmpty()) {
            return InteractionResult.PASS;
        }
        DungeonLog log = DungeonLog.forServer(serverPlayer.level().getServer());
        if (log.get(serverPlayer.getUUID()).unlockedShells().contains(shellName)) {
            Chime.refused(serverPlayer);
            serverPlayer.sendSystemMessage(Component.literal(
                    "You have already unlocked that shell.").withStyle(ChatFormatting.YELLOW));
            return InteractionResult.SUCCESS_SERVER;
        }
        held.shrink(1);
        log.unlockShell(serverPlayer.getUUID(), shellName);
        RoomBuilder.ShellPalette palette = RoomBuilder.palette(shellName);
        Chime.fanfare(serverPlayer);
        serverPlayer.sendSystemMessage(Component.literal(
                "Unlocked the " + palette.displayName() + " shell. It is yours permanently.")
                .withStyle(ChatFormatting.GOLD));
        serverPlayer.sendSystemMessage(Component.literal(
                "Apply it from your room's menu under Change Shell.")
                .withStyle(ChatFormatting.YELLOW));
        return InteractionResult.SUCCESS_SERVER;
    }

    /** The token a chest table would pay out, built here so tests mint the same item. An echo shard, not a block, so a right-click on the floor reaches the claim instead of placing it. */
    static ItemStack token(String shellName) {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.ECHO_SHARD, 1);
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, tag -> {
                    net.minecraft.nbt.CompoundTag mine = new net.minecraft.nbt.CompoundTag();
                    mine.putString(RoomBuilder.KEY_SHELL_UNLOCK, shellName);
                    tag.put(PocketDungeonsMod.MOD_ID, mine);
                });
        return stack;
    }
}
