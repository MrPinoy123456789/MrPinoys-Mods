package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.Wallet;
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

/**
 * The only class that touches both an inventory and a balance. Everything about item
 * duplication lives here or nowhere.
 *
 * <p>The arithmetic is not here -- it is in {@link Wallet}, in the core module, under
 * test. This class reads slots into {@code int[]}, asks Wallet what should happen, and
 * applies the answer. That split is the point: the part that can be wrong is the part
 * that can be tested.
 *
 * <p>Every method takes an {@link Item} rather than assuming cobblestone, which is
 * what lets one implementation serve both currencies and the shop's deliveries.
 *
 * <p><b>Why slots 0-35 only.</b> The player's main inventory is the first 36 slots
 * (hotbar 0-8, then the three rows) and has been for far longer than the surrounding
 * API has been stable. Armour and offhand indices have moved between versions; relying
 * on {@code getContainerSize()} to mean the same thing on two versions is how a mod
 * starts inserting cobblestone into a helmet slot. The conservative reading costs a
 * player having to move a stack out of their offhand before {@code /bank all} sees it.
 * The failure in the other direction is a dupe.
 */
public final class ItemBank {

    /** Hotbar plus the three main rows. */
    private static final int MAIN_SLOTS = 36;

    private ItemBank() {}

    /**
     * Resolve {@code minecraft:ice} to an item, or empty if the id is not real.
     *
     * <p>{@code Identifier} is what 26.1 renamed {@code ResourceLocation} to -- Mojang
     * adopted the name Yarn had used for years. Any tutorial written before 26.1 calls
     * it the old name.
     */
    public static Optional<Item> resolve(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return Optional.empty();
        return BuiltInRegistries.ITEM.getOptional(id);
    }

    /** The item backing a currency. Absent only if the registry has no such item. */
    public static Optional<Item> itemFor(Currency currency) {
        return resolve(currency.itemId());
    }

    /** Stack size for an item, read from the item rather than assumed to be 64. */
    public static int maxStack(Item item) {
        return new ItemStack(item).getMaxStackSize();
    }

    /** How much of this item the player is carrying. */
    public static long count(ServerPlayer player, Item item) {
        return Wallet.count(counts(player, item));
    }

    /** How much more of this item would fit in the player's main inventory. */
    public static long freeCapacity(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int max = maxStack(item);
        int empty = 0;
        List<Integer> partials = new ArrayList<>();

        for (int slot = 0; slot < limit(inv); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) {
                empty++;
            } else if (stack.is(item)) {
                partials.add(stack.getCount());
            }
            // Anything else contributes nothing and is deliberately not represented.
        }
        return Wallet.freeCapacity(empty, toArray(partials), max);
    }

    /**
     * Remove exactly {@code amount} of an item, or remove nothing at all.
     *
     * <p>All-or-nothing is enforced by {@link Wallet#planRemoval} returning null when
     * the inventory is short. No code path here removes some items and then discovers
     * a problem, because the discovery happens before the first write.
     *
     * @return true if the items were removed and the caller may now credit the bank
     */
    public static boolean removeExactly(ServerPlayer player, Item item, long amount) {
        Inventory inv = player.getInventory();

        List<Integer> slots = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int slot = 0; slot < limit(inv); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                slots.add(slot);
                counts.add(stack.getCount());
            }
        }

        int[] plan = Wallet.planRemoval(toArray(counts), amount);
        if (plan == null) return false;

        for (int i = 0; i < plan.length; i++) {
            if (plan[i] <= 0) continue;
            int slot = slots.get(i);
            ItemStack stack = inv.getItem(slot);

            // Re-check the slot rather than trusting the snapshot. Commands run on the
            // server thread so nothing should have moved, but this is the one place
            // where being wrong mints currency, and the check is free.
            if (!stack.is(item) || stack.getCount() < plan[i]) {
                CobbleEconomyMod.LOG.error("Inventory changed underneath a deposit for {} -- aborting",
                        player.getName().getString());
                return false;
            }
            stack.shrink(plan[i]);
            if (stack.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
        }

        player.containerMenu.broadcastChanges();
        return true;
    }

    /**
     * Give the player {@code amount} of an item. The caller must have already checked
     * {@link #freeCapacity} and debited the bank.
     *
     * <p>Anything that somehow will not fit is dropped at the player's feet rather
     * than deleted -- the same giveOrDrop rule the other mods in this set use. The
     * bank has already been debited by this point, so a stack that vanished here would
     * be currency destroyed with no record.
     *
     * @return how many items had to be dropped rather than placed; 0 in the normal case
     */
    public static long give(ServerPlayer player, Item item, long amount) {
        return give(player, item, amount, null);
    }

    /**
     * Give the player {@code amount} of an item, stamping {@code patch} onto every
     * stack handed over. Used by shop listings that sell a component-marked item -- a
     * Spirit Stone is an echo shard plus {@code custom_data}, and delivering the plain
     * item would hand over something that does nothing.
     *
     * <p>The stack size is read from a patched sample rather than the bare item,
     * because {@code max_stack_size} is itself a component and a patch is allowed to
     * change it.
     *
     * @param patch components to apply, or null to deliver the plain item
     * @return how many items had to be dropped rather than placed; 0 in the normal case
     */
    public static long give(ServerPlayer player, Item item, long amount,
                            DataComponentPatch patch) {
        int max = maxStack(item, patch);
        long[] split = Wallet.splitIntoStacks(amount, max);
        long dropped = 0;

        for (long i = 0; i < split[0]; i++) {
            dropped += insertOrDrop(player, stack(item, max, patch));
        }
        if (split[1] > 0) {
            dropped += insertOrDrop(player, stack(item, (int) split[1], patch));
        }

        player.containerMenu.broadcastChanges();
        return dropped;
    }

    /** Stack size for an item once {@code patch} has been applied to it. */
    private static int maxStack(Item item, DataComponentPatch patch) {
        return patch == null ? maxStack(item) : stack(item, 1, patch).getMaxStackSize();
    }

    private static ItemStack stack(Item item, int count, DataComponentPatch patch) {
        ItemStack stack = new ItemStack(item, count);
        if (patch != null) stack.applyComponents(patch);
        return stack;
    }

    private static long insertOrDrop(ServerPlayer player, ItemStack stack) {
        if (player.getInventory().add(stack)) return 0;
        // add() may have placed part of it; the stack is mutated to what is left.
        int leftover = stack.getCount();
        if (leftover > 0) player.drop(stack, false);
        return leftover;
    }

    private static int[] counts(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        List<Integer> counts = new ArrayList<>();
        for (int slot = 0; slot < limit(inv); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) counts.add(stack.getCount());
        }
        return toArray(counts);
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
