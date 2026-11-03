package excellentenchants;

import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Enchantments taken out of the game. Each stays registered in the data pack, because an item naming an
 * enchantment the registry no longer has can fail to load; instead it can no longer be rolled, does nothing,
 * and is taken off any item a player carries (inventory and ender chest) when they join and every five seconds.
 * An enchanted book left with nothing on it becomes a plain book.
 *
 * <p>Retired so far: Silk Chest (picked a chest up with everything in it).
 */
public final class Retired {

    public static final Set<ResourceKey<Enchantment>> KEYS = Set.of(Enchants.SILK_CHEST);

    private Retired() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> stripPlayer(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 100 != 0) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) stripPlayer(player);
        });
    }

    /** Strips every carried item; returns how many items changed. */
    public static int stripPlayer(ServerPlayer player) {
        int changed = stripAll(player.getInventory()) + stripAll(player.getEnderChestInventory());
        if (changed > 0) {
            player.sendSystemMessage(Component.literal("Silk Chest has been retired, so it was taken off "
                    + (changed == 1 ? "one of your items." : changed + " of your items.")).withStyle(ChatFormatting.GRAY));
        }
        return changed;
    }

    private static int stripAll(Container container) {
        int changed = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack before = container.getItem(i);
            ItemStack after = strip(before);
            if (after == null) continue;
            if (after != before) container.setItem(i, after);
            changed++;
        }
        if (changed > 0) container.setChanged();
        return changed;
    }

    /**
     * Takes the retired enchantments off one stack. Returns null when it carried none; otherwise the stack to
     * keep, which is the same stack, or a plain book when an enchanted book is left empty.
     */
    public static ItemStack strip(ItemStack stack) {
        if (stack.isEmpty() || !carries(stack)) return null;
        EnchantmentHelper.updateEnchantments(stack, enchants -> enchants.removeIf(Retired::retired));
        if (stack.is(Items.ENCHANTED_BOOK) && !EnchantmentHelper.hasAnyEnchantments(stack)) {
            return new ItemStack(Items.BOOK, stack.getCount());
        }
        return stack;
    }

    private static boolean carries(ItemStack stack) {
        boolean[] found = {false};
        EnchantmentHelper.updateEnchantments(stack.copy(), enchants -> {
            for (Holder<Enchantment> holder : enchants.keySet()) if (retired(holder)) found[0] = true;
        });
        return found[0];
    }

    private static boolean retired(Holder<Enchantment> holder) {
        for (ResourceKey<Enchantment> key : KEYS) if (holder.is(key)) return true;
        return false;
    }
}
