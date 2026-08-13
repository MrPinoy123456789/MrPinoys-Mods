package wayfarers.core;

/**
 * A simple two-currency purse used for transaction arithmetic and tests.
 *
 * <p>The Fabric layer maps the 36 main inventory slots to and from this.
 */
public final class Wallet {
    private int diamond;
    private int cobble;

    public Wallet(int diamond, int cobble) {
        this.diamond = diamond;
        this.cobble = cobble;
    }

    public int diamond() { return diamond; }
    public int cobble() { return cobble; }

    public int get(Currencies c) {
        return c == Currencies.DIAMOND ? diamond : cobble;
    }

    public boolean canAfford(Currencies c, int amount) {
        return get(c) >= amount;
    }

    public void add(Currencies c, int amount) {
        if (c == Currencies.DIAMOND) {
            diamond += amount;
        } else if (c == Currencies.COBBLE || c == Currencies.COBBLESTONE) {
            cobble += amount;
        }
    }

    public void remove(Currencies c, int amount) {
        add(c, -amount);
    }

    public void apply(Purchase.Plan plan) {
        // A positive plan cost is paid by the player; a negative cost is paid to them.
        add(plan.currency(), -plan.cost());
    }

    // ---------------------------------------------------------------- inventory math

    /** Total of all positive counts in the array. */
    public static long count(int[] stacks) {
        long total = 0L;
        for (int c : stacks) {
            if (c > 0) total += c;
        }
        return total;
    }

    /** How much more would fit given empty slots and partial stacks of this item. */
    public static long freeCapacity(int emptySlots, int[] partialStacks, int maxStack) {
        long capacity = (long) Math.max(0, emptySlots) * maxStack;
        for (int c : partialStacks) {
            if (c > 0 && c < maxStack) capacity += (maxStack - c);
        }
        return capacity;
    }

    /**
     * Work out exactly how much to take from each slot to reach {@code amount},
     * or return {@code null} if the inventory does not hold enough.
     *
     * <p>Returning null rather than a best-effort plan is deliberate; the caller
     * cannot accidentally apply a partial removal.
     */
    public static int[] planRemoval(int[] stacks, long amount) {
        if (amount <= 0) return null;
        if (count(stacks) < amount) return null;

        Integer[] order = new Integer[stacks.length];
        for (int i = 0; i < stacks.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(stacks[a], stacks[b]));

        int[] plan = new int[stacks.length];
        long remaining = amount;
        for (int idx : order) {
            if (remaining <= 0) break;
            int available = Math.max(0, stacks[idx]);
            if (available == 0) continue;
            int take = (int) Math.min(available, remaining);
            plan[idx] = take;
            remaining -= take;
        }
        if (remaining != 0) return null;
        return plan;
    }

    /** Split an amount into whole stacks plus a remainder. */
    public static long[] splitIntoStacks(long amount, int maxStack) {
        if (amount <= 0 || maxStack <= 0) return new long[] {0L, 0L};
        return new long[] {amount / maxStack, amount % maxStack};
    }
}
