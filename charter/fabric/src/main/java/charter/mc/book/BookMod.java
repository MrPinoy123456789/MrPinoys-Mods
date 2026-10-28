package charter.mc.book;

import charter.mc.CharterServer;
import charter.mc.Text;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** The Charter book and the founding Nexus: one register call from {@code CharterMod}. */
public final class BookMod {

    private BookMod() {
    }

    /**
     * Hands a book to a joining player who carries none (C2). Nothing is saved: a
     * player who loses their book gets another the next time they join.
     */
    public static void onJoin(ServerPlayer player) {
        if (!CharterServer.running()) return;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (CharterBook.isBook(inventory.getItem(i))) return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (CharterBook.isBook(player.getItemBySlot(slot))) return;
        }
        ItemStack book = CharterBook.create();
        giveOrDrop(player, book);
        player.sendSystemMessage(Component.literal(Text.get("book.given")).withStyle(ChatFormatting.GOLD));
    }

    /** Into the inventory, or at the player's feet when it does not fit. Null when it fitted. */
    public static net.minecraft.world.entity.item.ItemEntity giveOrDrop(ServerPlayer player, ItemStack stack) {
        return player.getInventory().add(stack) ? null : player.drop(stack, false);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, Identifier.fromNamespaceAndPath("charter", "nexus"),
                NexusRecipe.SERIALIZER);
        CharterBook.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BookDialogs.LAST.remove(handler.player.getUUID()));
    }
}
