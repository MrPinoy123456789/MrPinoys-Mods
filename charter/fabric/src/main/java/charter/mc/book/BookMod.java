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
        ensure(player, false);
    }

    /** True when the player carries a Charter book anywhere on them. */
    public static boolean carriesBook(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (CharterBook.isBook(inventory.getItem(i))) return true;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (CharterBook.isBook(player.getItemBySlot(slot))) return true;
        }
        return false;
    }

    /**
     * Gives the player a book if they carry none. On console and Bedrock the book is the only way into the menu, since
     * there is no typing a command, so a lost one is replaced at once and not only when they next join.
     */
    public static void ensure(ServerPlayer player, boolean lost) {
        if (carriesBook(player)) return;
        giveOrDrop(player, CharterBook.create());
        player.sendSystemMessage(Component.literal(Text.get(lost ? "book.replaced" : "book.given")).withStyle(ChatFormatting.GOLD));
    }

    /** One pass over everyone online: whoever has no book gets one. */
    public static void ensureAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isAlive() && !player.isSpectator()) ensure(player, true);
        }
    }

    /** Into the inventory, or at the player's feet when it does not fit. Null when it fitted. */
    public static net.minecraft.world.entity.item.ItemEntity giveOrDrop(ServerPlayer player, ItemStack stack) {
        return player.getInventory().add(stack) ? null : player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, Identifier.fromNamespaceAndPath("charter", "nexus"),
                NexusRecipe.SERIALIZER);
        CharterBook.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BookDialogs.LAST.remove(handler.player.getUUID()));
    }
}
