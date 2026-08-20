package cobblebending;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Cobblestone is the ammunition. Count first, consume second.
 */
public final class Ammo {

    private Ammo() {}

    /** Ordered offhand -> hotbar 0-8 -> main inventory 9-35. */
    public static int count(ServerPlayer player) {
        int total = 0;
        Inventory inv = player.getInventory();
        int[] order = order(inv);
        for (int slot : order) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(Items.COBBLESTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Removes the requested cobblestone in inventory priority order.
     *
     * @return whether the full amount was available and consumed
     */
    public static boolean consume(ServerPlayer player, int amount) {
        if (count(player) < amount) {
            return false;
        }
        Inventory inv = player.getInventory();
        int remaining = amount;
        for (int slot : order(inv)) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.is(Items.COBBLESTONE)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            if (stack.isEmpty()) {
                inv.setItem(slot, ItemStack.EMPTY);
            }
            remaining -= take;
            if (remaining == 0) {
                return true;
            }
        }
        return false;
    }

    /** Tells the player how much cobblestone the attempted ability requires and lacks. */
    public static void notifyShortfall(ServerPlayer player, int need) {
        int have = count(player);
        player.sendOverlayMessage(
                Component.literal("Not enough cobblestone (need " + need + ", have " + have + ")")
                        .withStyle(ChatFormatting.RED)
        );
    }

    /**
     * Checks an ability cost and reports any shortfall to the player.
     *
     * @return whether the player currently has enough cobblestone
     */
    public static boolean canPay(ServerPlayer player, int amount) {
        if (count(player) < amount) {
            notifyShortfall(player, amount);
            return false;
        }
        return true;
    }

    /** Returns cobblestone to inventory, dropping it when no slot can accept it. */
    public static void give(ServerPlayer player, int amount) {
        ItemStack stack = new ItemStack(Items.COBBLESTONE, amount);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static int[] order(Inventory inv) {
        List<Integer> list = new ArrayList<>();
        int offhand = Inventory.SLOT_OFFHAND;
        if (offhand < inv.getContainerSize()) {
            list.add(offhand);
        }
        for (int i = 0; i < 9; i++) {
            list.add(i);
        }
        for (int i = 9; i < Math.min(36, inv.getContainerSize()); i++) {
            list.add(i);
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }
}
