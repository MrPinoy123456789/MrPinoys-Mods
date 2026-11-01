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
        removeExtras(player);
        if (carriesBook(player)) return;
        giveOrDrop(player, CharterBook.create());
        player.sendSystemMessage(Component.literal(Text.get(lost ? "book.replaced" : "book.given")).withStyle(ChatFormatting.GOLD));
    }

    /**
     * A player never keeps more than one Charter book. The first one found (hotbar first, then the rest of the
     * inventory, armour and off hand, and last the stack on the cursor) stays; every other one is deleted. This is
     * what makes a looted book, or the old one lying where its owner died, harmless. Returns how many were deleted.
     */
    public static int removeExtras(ServerPlayer player) {
        var inventory = player.getInventory();
        boolean kept = false;
        ItemStack keeper = null;
        int removed = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!CharterBook.isBook(inventory.getItem(i))) continue;
            if (!kept) {
                kept = true;
                keeper = inventory.getItem(i);
            } else {
                inventory.setItem(i, ItemStack.EMPTY);
                removed++;
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            // The inventory above may already cover armour and the off hand: the same stack is never counted twice.
            ItemStack worn = player.getItemBySlot(slot);
            if (!CharterBook.isBook(worn) || worn == keeper) continue;
            if (!kept) {
                kept = true;
                keeper = worn;
            } else {
                player.setItemSlot(slot, ItemStack.EMPTY);
                removed++;
            }
        }
        if (player.containerMenu != null && CharterBook.isBook(player.containerMenu.getCarried())) {
            if (kept) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
                removed++;
            }
        }
        if (removed > 0) {
            player.containerMenu.broadcastChanges();
            player.sendSystemMessage(Component.literal(Text.get("book.duplicate")).withStyle(ChatFormatting.GOLD));
        }
        return removed;
    }

    /** Once a second for everyone online: a second book is deleted, whether or not lost books are replaced. */
    public static void removeExtrasAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isAlive() && !player.isSpectator()) removeExtras(player);
        }
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

    /** Deletes every Charter book a player carries, so none is dropped on death. Returns how many. */
    public static int vanish(ServerPlayer player) {
        int n = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (CharterBook.isBook(inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
                n++;
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (CharterBook.isBook(player.getItemBySlot(slot))) {
                player.setItemSlot(slot, ItemStack.EMPTY);
                n++;
            }
        }
        return n;
    }

    public static void register() {
        // Before the death loot is dropped: the book never lies on the ground, and respawn hands out a fresh one.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player) vanish(player);
            return true;
        });
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, Identifier.fromNamespaceAndPath("charter", "nexus"),
                NexusRecipe.SERIALIZER);
        CharterBook.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BookDialogs.LAST.remove(handler.player.getUUID()));
    }
}
