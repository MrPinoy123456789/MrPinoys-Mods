package chatdonkey;

import chatdonkey.core.Gift;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * The suite's giveOrDrop rule: add to the inventory, drop at the player's feet
 * if it will not fit. A reward is never destroyed (DESIGN.md section 4.1).
 */
public final class Rewards {

    private Rewards() {}

    /**
     * Hands over a resolved gift (SPEC.md section 5).
     *
     * <p>The named items use {@code item_name} and {@code lore} components only
     * -- no {@code custom_data}, because nothing here has behavior. A vanilla
     * client renders both without installing anything.
     */
    public static void giveGift(ServerPlayer player, Gift gift) {
        switch (gift.tier()) {
            case GRUDGE -> {
                // Nothing. The exit line is the gift.
            }
            case STANDARD -> giveCobblestone(player, gift.count());
            case SATISFIED -> giveCobblestone(player, gift.count());
            case GRACIOUS -> {
                giveCobblestone(player, gift.count());
                giveStack(player, apologyCarrot());
            }
            case GOLDEN -> giveStack(player, guiltDiamond());
        }
    }

    /** A carrot the donkey is very slightly sorry with. */
    private static ItemStack apologyCarrot() {
        ItemStack stack = new ItemStack(Items.CARROT, 1);
        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Apology Carrot").withStyle(ChatFormatting.YELLOW));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Given under protest.").withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    /** The rare one. The donkey would like it on record that it does not feel bad. */
    private static ItemStack guiltDiamond() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 1);
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("The donkey felt bad. Not really.")
                        .withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    public static void giveCobblestone(ServerPlayer player, int count) {
        give(player, Items.COBBLESTONE, count);
    }

    private static void giveStack(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
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
