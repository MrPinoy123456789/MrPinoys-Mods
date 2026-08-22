package wondrous.api;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Handing an item to a player without losing it.
 *
 * <p>Lives in the API module rather than the mod because every consumer needs it:
 * a reward that silently vanishes into a full inventory is the worst possible
 * outcome for a daily streak, and each mod solving that separately means each mod
 * getting it wrong separately.
 */
public final class WondrousGive {

    private WondrousGive() {}

    /**
     * Adds to the player's inventory, or drops at their feet if it will not fit,
     * telling them either way. Never fails quietly.
     */
    public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.drop(stack, false);
            player.sendSystemMessage(Component.literal(
                            "Your inventory was full -- dropped at your feet.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
