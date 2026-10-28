package charter.mc.book;

import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Opens a written book on a player's screen without giving it to them. Copied
 * from Pocket Dungeons' {@code DiaryReading.openBook} (a reference, never a
 * dependency).
 *
 * <p>The book is shown in the selected hotbar slot for the length of the open
 * packet and the slot is put back at once, so the server's inventory is never
 * touched: a crash or a server stop cannot delete the held item or leave a book
 * behind.
 */
final class BookReading {

    private BookReading() {
    }

    static void open(ServerPlayer player, ItemStack book) {
        int slot = player.getInventory().getSelectedSlot();
        player.connection.send(new ClientboundSetPlayerInventoryPacket(slot, book));
        player.connection.send(new ClientboundOpenBookPacket(InteractionHand.MAIN_HAND));
        player.connection.send(new ClientboundSetPlayerInventoryPacket(slot, player.getInventory().getItem(slot).copy()));
    }
}
