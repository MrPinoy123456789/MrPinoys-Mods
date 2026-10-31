package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import chartercurrency.core.Wallet;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The only class that touches both an inventory and a balance. Everything about item
 * duplication lives here or nowhere.
 *
 * <p>The arithmetic is not here; it is in {@link Wallet}, in the core module, under
 * test. This class reads slots into {@code int[]}, asks Wallet what should happen, and
 * applies the answer. That split is the point: the part that can be wrong is the part
 * that can be tested.
 *
 * <p>Stacks are matched with a predicate. A coin is matched by its custom_data marker
 * (see {@link CoinStacks}); a shop delivery is matched by item. Counting and removing
 * use the predicate; free-space checks are stricter, because two stacks only merge when
 * item and components are identical.
 *
 * <p><b>Why slots 0-35 only.</b> The player's main inventory is the first 36 slots
 * (hotbar 0-8, then the three rows). Armour and offhand indices have moved between
 * versions; relying on {@code getContainerSize()} to mean the same thing on two
 * versions is how a mod starts inserting coins into a helmet slot. The cost is that a
 * stack in the offhand is not seen by {@code /bank all}. The failure in the other
 * direction is a dupe.
 */
public final class ItemBank {

    /** Hotbar plus the three main rows. */
    private static final int MAIN_SLOTS = 36;

    private ItemBank() {}

    /** Resolve {@code minecraft:ice} to an item, or empty if the id is not real. */
    public static Optional<Item> resolve(String itemId) {
        if (itemId == null) return Optional.empty();
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return Optional.empty();
        return BuiltInRegistries.ITEM.getOptional(id);
    }

    /**
     * An item to draw a currency with: its single item, else its largest coin. Empty
     * for a currency with no item at all (Valor).
     */
    public static Optional<Item> itemFor(Currency currency) {
        if (currency.itemId() != null) return resolve(currency.itemId());
        List<Denomination> coins = currency.largestFirst();
        if (coins.isEmpty()) return Optional.empty();
        return resolve(coins.get(0).itemId());
    }

    /** Stack size for an item, read from the item rather than assumed to be 64. */
    public static int maxStack(Item item) {
        return new ItemStack(item).getMaxStackSize();
    }

    // ---- by item ------------------------------------------------------------

    /** How much of this item the player is carrying. */
    public static long count(ServerPlayer player, Item item) {
        return count(player, stack -> stack.is(item));
    }

    /** How much more of this item would fit in the player's main inventory. */
    public static long freeCapacity(ServerPlayer player, Item item) {
        return freeCapacity(player, new ItemStack(item));
    }

    /** Remove exactly {@code amount} of an item, or remove nothing at all. */
    public static boolean removeExactly(ServerPlayer player, Item item, long amount) {
        return removeExactly(player, stack -> stack.is(item), amount);
    }

    // ---- by predicate -------------------------------------------------------

    /** How many matching items the player carries across the main 36 slots. */
    public static long count(ServerPlayer player, Predicate<ItemStack> match) {
        return Wallet.count(counts(player, match));
    }

    /** Completely empty slots in the main 36. */
    public static int emptySlots(ServerPlayer player) {
        Inventory inv = player.getInventory();
        int empty = 0;
        for (int slot = 0; slot < limit(inv); slot++) {
            if (inv.getItem(slot).isEmpty()) empty++;
        }
        return empty;
    }

    /**
     * Room left in stacks that would merge with {@code prototype}: same item and
     * identical components. A near-identical stack does not merge, so it adds nothing.
     */
    public static long partialRoom(ServerPlayer player, ItemStack prototype) {
        Inventory inv = player.getInventory();
        int max = prototype.getMaxStackSize();
        long room = 0;
        for (int slot = 0; slot < limit(inv); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, prototype)
                    && stack.getCount() < max) {
                room += max - stack.getCount();
            }
        }
        return room;
    }

    /** How many more of {@code prototype} would fit in the main inventory. */
    public static long freeCapacity(ServerPlayer player, ItemStack prototype) {
        return Wallet.freeCapacity(emptySlots(player),
                partialStacks(player, prototype), prototype.getMaxStackSize());
    }

    /**
     * Remove exactly {@code amount} of the matching items, or remove nothing at all.
     *
     * <p>All-or-nothing is enforced by {@link Wallet#planRemoval} returning null when
     * the inventory is short. No code path here removes some items and then discovers
     * a problem, because the discovery happens before the first write.
     *
     * @return true if the items were removed and the caller may now credit the bank
     */
    public static boolean removeExactly(ServerPlayer player, Predicate<ItemStack> match, long amount) {
        Inventory inv = player.getInventory();

        List<Integer> slots = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int slot = 0; slot < limit(inv); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && match.test(stack)) {
                slots.add(slot);
                counts.add(stack.getCount());
            }
        }

        int[] plan = Wallet.planRemoval(toArray(counts), amount);
        if (plan == null) return false;

        for (int i = 0; i < plan.length; i++) {
            if (plan[i] <= 0) continue;
            ItemStack stack = inv.getItem(slots.get(i));

            // Re-check the slot rather than trusting the snapshot. Commands run on the
            // server thread so nothing should have moved, but this is the one place
            // where being wrong mints currency, and the check is free.
            if (!match.test(stack) || stack.getCount() < plan[i]) {
                CharterCurrencyMod.LOG.error("Inventory changed underneath a deposit for {} - aborting",
                        player.getName().getString());
                return false;
            }
        }

        for (int i = 0; i < plan.length; i++) {
            if (plan[i] <= 0) continue;
            int slot = slots.get(i);
            ItemStack stack = inv.getItem(slot);
            stack.shrink(plan[i]);
            if (stack.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
        }

        player.containerMenu.broadcastChanges();
        return true;
    }

    // ---- giving -------------------------------------------------------------

    /**
     * Give the player {@code amount} of an item. The caller must have already checked
     * {@link #freeCapacity} and debited the bank.
     *
     * <p>Anything that somehow will not fit is dropped at the player's feet rather
     * than deleted: the giveOrDrop rule. The bank has already been debited by this
     * point, so a stack that vanished here would be currency destroyed with no record.
     *
     * @return how many items had to be dropped rather than placed; 0 in the normal case
     */
    public static long give(ServerPlayer player, Item item, long amount) {
        return give(player, item, amount, null);
    }

    /**
     * Give the player {@code amount} of an item, stamping {@code patch} onto every
     * stack handed over. The stack size is read from a patched sample, because
     * {@code max_stack_size} is itself a component.
     *
     * @param patch components to apply, or null to deliver the plain item
     * @return how many items had to be dropped rather than placed; 0 in the normal case
     */
    public static long give(ServerPlayer player, Item item, long amount, DataComponentPatch patch) {
        ItemStack sample = new ItemStack(item, 1);
        if (patch != null) sample.applyComponents(patch);
        return give(player, sample, amount);
    }

    /** Give {@code amount} copies of a prototype stack, dropping what does not fit. */
    public static long give(ServerPlayer player, ItemStack prototype, long amount) {
        int max = prototype.getMaxStackSize();
        long[] split = Wallet.splitIntoStacks(amount, max);
        long dropped = 0;

        for (long i = 0; i < split[0]; i++) {
            dropped += insertOrDrop(player, prototype.copyWithCount(max));
        }
        if (split[1] > 0) {
            dropped += insertOrDrop(player, prototype.copyWithCount((int) split[1]));
        }

        player.containerMenu.broadcastChanges();
        return dropped;
    }

    private static long insertOrDrop(ServerPlayer player, ItemStack stack) {
        // add() may place only part of the stack and still return true; it mutates
        // the stack down to the remainder, so the remainder is what to check.
        player.getInventory().add(stack);
        int leftover = stack.getCount();
        if (leftover > 0) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
        return leftover;
    }

    // ---- internals ----------------------------------------------------------

    private static int[] counts(ServerPlayer player, Predicate<ItemStack> match) {
        Inventory inv = player.getInventory();
        List<Integer> counts = new ArrayList<>();
        for (int slot = 0; slot < limit(inv); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && match.test(stack)) counts.add(stack.getCount());
        }
        return toArray(counts);
    }

    private static int[] partialStacks(ServerPlayer player, ItemStack prototype) {
        Inventory inv = player.getInventory();
        List<Integer> partials = new ArrayList<>();
        for (int slot = 0; slot < limit(inv); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, prototype)) {
                partials.add(stack.getCount());
            }
        }
        return toArray(partials);
    }

    private static int limit(Inventory inv) {
        return Math.min(MAIN_SLOTS, inv.getContainerSize());
    }

    private static int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
