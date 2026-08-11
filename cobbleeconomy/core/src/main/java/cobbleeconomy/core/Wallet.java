package cobbleeconomy.core;

/**
 * The arithmetic of moving cobblestone in and out of an inventory, with no
 * inventory in sight.
 *
 * <p>This class exists because the spec's two scariest requirements -- never delete
 * currency into a full inventory, never let a partial removal desync from a balance
 * change -- are both pure arithmetic wearing a Minecraft costume. Left inside the
 * command handler they are untestable without a running server, which is exactly how
 * duplication bugs survive to production. Here they are a handful of loops the test
 * suite hammers directly.
 *
 * <p>The Fabric layer's only job is to translate slots into {@code int[]} and back,
 * and to apply the plan this class returns.
 */
public final class Wallet {

    private Wallet() {}

    /**
     * How many cobblestone the player is holding, saturating at {@link Long#MAX_VALUE}.
     *
     * @param stacks the count in each slot that currently holds cobblestone
     */
    public static long count(int[] stacks) {
        long total = 0L;
        for (int c : stacks) {
            if (c > 0) total += c;
        }
        return total;
    }

    /**
     * How many more cobblestone would fit.
     *
     * <p>Empty slots take a full stack; slots already holding cobblestone take
     * whatever is left of theirs. Slots holding anything else are the caller's job to
     * leave out -- a slot full of dirt contributes nothing and is not represented.
     *
     * @param emptySlots     count of completely empty slots
     * @param partialStacks  the count in each slot already holding cobblestone
     * @param maxStack       stack size for cobblestone; 64 in vanilla
     */
    public static long freeCapacity(int emptySlots, int[] partialStacks, int maxStack) {
        long capacity = (long) Math.max(0, emptySlots) * maxStack;
        for (int c : partialStacks) {
            if (c > 0 && c < maxStack) capacity += (maxStack - c);
        }
        return capacity;
    }

    /**
     * Work out exactly how much to take from each cobblestone slot to reach
     * {@code amount}, or return {@code null} if the inventory does not hold enough.
     *
     * <p>Returning null rather than a best-effort plan is deliberate and is the
     * whole atomicity story for deposits: the caller cannot accidentally apply a
     * partial removal, because a partial removal is not something this can produce.
     *
     * <p>Drains the smallest stacks first, which leaves the inventory tidier after a
     * partial {@code /bank} -- you get back one big stack rather than a row of
     * half-empty ones. Purely cosmetic; the totals are identical either way.
     *
     * @return per-slot amounts to remove, index-aligned with {@code stacks}, or null
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
        // Unreachable: the count check above guarantees it. Kept as a hard stop so a
        // future edit that breaks the invariant fails loudly instead of quietly
        // removing the wrong number of items.
        if (remaining != 0) return null;
        return plan;
    }

    /**
     * Split an amount into whole stacks plus a remainder, for handing to the game.
     *
     * <p>Withdrawing 5,000 means building 79 stacks of 64 and one of 16. The caller
     * needs the shape of that before it creates a single item, because it has already
     * promised the transaction is atomic.
     *
     * @return {@code [fullStacks, remainder]}
     */
    public static long[] splitIntoStacks(long amount, int maxStack) {
        if (amount <= 0 || maxStack <= 0) return new long[] {0L, 0L};
        return new long[] {amount / maxStack, amount % maxStack};
    }

    /**
     * Group separators for chat. {@code 12481} becomes {@code 12,481}.
     *
     * <p>Hand-rolled rather than {@link java.text.NumberFormat} so that a server
     * running under a locale using {@code .} as the separator does not render a
     * balance that reads like a decimal.
     */
    public static String format(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder out = new StringBuilder();
        int lead = digits.length() % 3;
        if (lead == 0) lead = 3;
        out.append(digits, 0, lead);
        for (int i = lead; i < digits.length(); i += 3) {
            out.append(',').append(digits, i, i + 3);
        }
        return amount < 0 ? "-" + out : out.toString();
    }
}
