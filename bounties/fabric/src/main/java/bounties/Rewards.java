package bounties;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Pays diamond rewards into the player's inventory, dropping anything that does not fit.
 * Same giveOrDrop rule used by quizengine and cobbleeconomy.
 */
public final class Rewards {

    private Rewards() {}

    public static void giveDiamonds(ServerPlayer player, int count) {
        give(player, Items.DIAMOND, count);
    }

    private static void give(ServerPlayer player, Item item, int count) {
        int remaining = count;
        while (remaining > 0) {
            int size = Math.min(remaining, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, size);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= size;
        }
    }
}
