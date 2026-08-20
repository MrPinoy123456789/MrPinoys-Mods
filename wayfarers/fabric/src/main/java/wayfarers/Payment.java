package wayfarers;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import wayfarers.core.Currencies;
import wayfarers.core.Listing;
import wayfarers.core.Purchase;
import wayfarers.core.Wallet;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical payment: diamonds and cobblestone in the main 36 inventory slots.
 *
 * <p>Count first, apply second. A transaction that cannot complete in full
 * changes nothing. Payouts are added to the inventory; anything that does not fit
 * is dropped at the player's feet and logged.
 */
public final class Payment {

    private Payment() {}

    /** The current diamond/cobble count in the player's main 36 slots. */
    public static Wallet scan(ServerPlayer player) {
        int[] diamond = countsFor(player, Items.DIAMOND);
        int[] cobble = countsFor(player, Items.COBBLESTONE);
        return new Wallet((int) Wallet.count(diamond), (int) Wallet.count(cobble));
    }

    /**
     * Tries to buy one lot of {@code listing} from the player's inventory.
     *
     * @return the plan if the purchase succeeds, or null if it is refused
     */
    public static Purchase.Plan buy(ServerPlayer player, Listing listing) {
        Wallet wallet = scan(player);
        Purchase.Result result = Purchase.buy(listing, wallet);
        if (result.outcome() != Purchase.Outcome.OK) {
            player.sendSystemMessage(Component.literal("You cannot afford that.").withStyle(ChatFormatting.RED));
            return null;
        }

        Purchase.Plan plan = result.plan();
        ItemStack product = resolve(listing, player);
        product.setCount(plan.quantity());

        if (!canHold(player, product)) {
            player.sendSystemMessage(Component.literal("Not enough room in your inventory.").withStyle(ChatFormatting.RED));
            return null;
        }

        if (!applyCurrencyRemoval(player, plan.currency(), plan.cost())) {
            return null;
        }

        giveOrDrop(player, product);
        player.sendSystemMessage(Component.literal("Purchased " + plan.quantity() + " " + listing.item())
                .withStyle(ChatFormatting.GREEN));
        return plan;
    }

    /**
     * Tries to sell one lot of {@code listing} from the player to the trader.
     * The trader pays from its {@code coin} budget.
     *
     * @return the plan if the sale succeeds, or null if it is refused
     */
    public static Purchase.Plan sell(ServerPlayer player, Encounters.Active active, Listing listing) {
        Wallet trader = new Wallet(active.coin, 0);
        Purchase.Result result = Purchase.sell(listing, trader, scan(player));
        if (result.outcome() == Purchase.Outcome.COIN_EXHAUSTED) {
            player.sendSystemMessage(Component.literal("The trader has no more coin for that.").withStyle(ChatFormatting.RED));
            return null;
        }
        if (result.outcome() != Purchase.Outcome.OK) {
            player.sendSystemMessage(Component.literal("That trade is not available.").withStyle(ChatFormatting.RED));
            return null;
        }

        Purchase.Plan plan = result.plan();
        ItemStack product = resolve(listing, player);
        if (product.isEmpty()) {
            player.sendSystemMessage(Component.literal("The trader is not buying that.").withStyle(ChatFormatting.RED));
            return null;
        }
        Item productItem = product.getItem();

        int[] have = countsFor(player, productItem);
        if (Wallet.count(have) < listing.quantity()) {
            player.sendSystemMessage(Component.literal("You do not have enough of that.").withStyle(ChatFormatting.RED));
            return null;
        }
        int[] removal = Wallet.planRemoval(have, listing.quantity());
        if (removal == null) {
            player.sendSystemMessage(Component.literal("You do not have enough of that.").withStyle(ChatFormatting.RED));
            return null;
        }

        int payout = -plan.cost();
        Item currencyItem = (plan.currency() == Currencies.DIAMOND) ? Items.DIAMOND : Items.COBBLESTONE;
        ItemStack payoutStack = new ItemStack(currencyItem, payout);
        if (!canHold(player, payoutStack)) {
            player.sendSystemMessage(Component.literal("Not enough room in your inventory.").withStyle(ChatFormatting.RED));
            return null;
        }

        applyRemovalPlan(player, productItem, removal);
        active.consumeCoin(payout);
        giveOrDrop(player, payoutStack);
        player.sendSystemMessage(Component.literal("Sold " + listing.quantity() + " " + listing.item() + " for " + payout)
                .withStyle(ChatFormatting.GREEN));
        return plan;
    }

    /** Resolve a listing into an item stack, applying component stamps if any. */
    private static ItemStack resolve(Listing listing, ServerPlayer player) {
        Identifier key = Identifier.tryParse(listing.item());
        if (key == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        ItemComponents.apply(stack, listing.components() == null ? "" : listing.components(), player.registryAccess());
        return stack;
    }

    /** @return true if the product fits in the main 36 slots. */
    private static boolean canHold(ServerPlayer player, ItemStack product) {
        int max = product.getMaxStackSize();
        int empty = 0;
        List<Integer> partial = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                empty++;
            } else if (ItemStack.isSameItemSameComponents(s, product) && s.getCount() < max) {
                partial.add(s.getCount());
            }
        }
        long need = product.getCount();
        int[] p = partial.stream().mapToInt(Integer::intValue).toArray();
        long capacity = Wallet.freeCapacity(empty, p, max);
        return capacity >= need;
    }

    /** Remove {@code amount} of {@code currency} from the main 36 slots. */
    private static boolean applyCurrencyRemoval(ServerPlayer player, Currencies currency, int amount) {
        Item currencyItem = (currency == Currencies.DIAMOND) ? Items.DIAMOND : Items.COBBLESTONE;
        int[] stacks = countsFor(player, currencyItem);
        int[] plan = Wallet.planRemoval(stacks, amount);
        if (plan == null) return false;
        return applyRemovalPlan(player, currencyItem, plan);
    }

    /** Apply a pre-computed removal plan to the main 36 slots. */
    static boolean applyRemovalPlan(ServerPlayer player, Item item, int[] plan) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (plan[i] <= 0) continue;
            ItemStack s = inv.getItem(i);
            if (s.getItem() == item) {
                s.shrink(plan[i]);
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        return true;
    }

    /** Add the stack to inventory; drop the remainder at the player's feet. */
    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        Inventory inv = player.getInventory();
        inv.add(stack);
        if (!stack.isEmpty()) {
            WayfarersMod.LOG.warn("Player {} could not fit {}; dropping at feet", player.getName().getString(), stack);
            player.drop(stack, false);
        }
    }

    /** Counts of the given item in each main inventory slot. */
    static int[] countsFor(ServerPlayer player, Item item) {
        int[] out = new int[36];
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            out[i] = (s.getItem() == item) ? s.getCount() : 0;
        }
        return out;
    }
}
