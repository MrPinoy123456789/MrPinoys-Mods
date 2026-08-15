package kamutotems;

import kamutotems.core.QuestSegment;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;

/**
 * Handles turn-in segments: count everything before removing anything.
 */
public final class TurnIn {

    private TurnIn() {}

    public static Component attempt(ServerPlayer player) {
        DailyChain.PlayerDay day = DailyChain.dayFor(player);
        if (day.completed()) {
            return Component.literal("You have already completed today's chain.").withStyle(ChatFormatting.GRAY);
        }

        Inventory inv = player.getInventory();
        for (int i = 0; i < day.chain().segments().size(); i++) {
            if (day.progress().segmentComplete(i, day.chain())) {
                continue;
            }
            QuestSegment seg = day.chain().segments().get(i);
            if (!"turn_in".equals(seg.kind())) {
                continue;
            }

            String itemId = seg.matcher().predicate().get("item");
            if (itemId == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            if (item == null || item == Items.AIR) {
                continue;
            }

            int needed = seg.matcher().requiredCount();
            int held = count(inv, item);
            if (held < needed) {
                continue;
            }

            remove(inv, item, needed);
            if (DailyChain.tryAdvance(player, "turn_in",
                    Map.of("eventId", "item_turned_in", "item", itemId))) {
                return null;
            }

            // If advance failed, refund the items so nothing is lost.
            ItemStack refund = new ItemStack(item, needed);
            if (!player.getInventory().add(refund)) {
                player.drop(refund, false);
            }
            return Component.literal("The hand-in could not be counted.").withStyle(ChatFormatting.RED);
        }

        return Component.literal("You are not carrying anything today's chain wants.")
                .withStyle(ChatFormatting.RED);
    }

    private static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void remove(Inventory inventory, Item item, int wanted) {
        int remaining = wanted;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            inventory.removeItem(i, take);
            remaining -= take;
        }
    }
}
