package chartercurrency.core;

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

    /** The shipped face values, index-aligned with copper, iron, gold. */
    public static final int[] DEFAULT_FACE_VALUES = {1, 8, 64};

    /**
     * What a deposit of coin does.
     *
     * @param take        coins to remove, per denomination, index-aligned with the
     *                    counts the caller supplied
     * @param taken       the Marks those coins are worth
     * @param change      Marks handed back because exact change was impossible
     * @param changeCoins the change as coins, index-aligned with the face values
     */
    public record DepositPlan(int[] take, long taken, long change, int[] changeCoins) {
        /** The Marks to credit to the bank: what was taken minus the change. */
        public long credited() {
            return taken - change;
        }
    }

    /** {@link #planDeposit(int[], int[], long)} with the shipped 1, 8, 64 face values. */
    public static DepositPlan planDeposit(int[] counts, long amountMarks) {
        return planDeposit(counts, DEFAULT_FACE_VALUES, amountMarks);
    }

    /**
     * Which coins to take for a deposit of {@code amountMarks}.
     *
     * <p>Exact change is used when the coins carried can make it. When they cannot (a
     * player holding only Sovereigns banks 100 Marks), the smallest overpayment is
     * taken and the difference comes back as change coins. Among plans with the same
     * total, fewer coins removed wins, then lower denominations first.
     *
     * <p>Returns null, never a partial plan, if the player does not carry enough in
     * total. The caller removes the coins first, credits second.
     *
     * @param counts      coins carried, per denomination
     * @param faceValues  face value of each denomination, index-aligned with counts
     * @param amountMarks the amount to deposit; must be positive
     */
    public static DepositPlan planDeposit(int[] counts, int[] faceValues, long amountMarks) {
        if (amountMarks <= 0 || counts.length != faceValues.length) return null;
        long carried = 0;
        for (int i = 0; i < counts.length; i++) carried += (long) Math.max(0, counts[i]) * faceValues[i];
        if (carried < amountMarks) return null;

        // Order denominations largest first. The two largest are enumerated; the rest
        // are filled greedily from below, which for the shipped chain (each face value
        // divides the next) finds the true minimum overpayment.
        Integer[] order = new Integer[faceValues.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(faceValues[b], faceValues[a]));

        long bestTotal = Long.MAX_VALUE;
        long bestCoins = Long.MAX_VALUE;
        int[] best = null;
        int[] trial = new int[counts.length];
        best = search(counts, faceValues, order, 0, amountMarks, 0L, 0L, trial,
                new long[] {bestTotal, bestCoins}, best);
        if (best == null) return null;

        long taken = 0;
        for (int i = 0; i < best.length; i++) taken += (long) best[i] * faceValues[i];
        long change = taken - amountMarks;
        return new DepositPlan(best, taken, change, makeChange(change, faceValues));
    }

    /**
     * Recursive enumeration over every denomination but the last (smallest), which is
     * resolved directly. Bounded by the product of the larger denominations' counts,
     * which for a 36-slot inventory of three coin types is a few million steps at the
     * absolute worst and is cut off as soon as a prefix already meets the amount.
     */
    private static int[] search(int[] counts, int[] faces, Integer[] order, int depth,
                                long amount, long sum, long coinsSoFar, int[] trial,
                                long[] bestRef, int[] best) {
        int idx = order[depth];
        boolean last = depth == order.length - 1;
        int max = Math.max(0, counts[idx]);

        if (last) {
            long short_ = amount - sum;
            int use;
            if (short_ <= 0) {
                use = 0;
            } else {
                long need = (short_ + faces[idx] - 1) / faces[idx];
                if (need > max) return best;
                use = (int) need;
            }
            trial[idx] = use;
            long total = sum + (long) use * faces[idx];
            long coins = coinsSoFar + use;
            if (total < bestRef[0] || (total == bestRef[0] && coins < bestRef[1])) {
                bestRef[0] = total;
                bestRef[1] = coins;
                best = trial.clone();
            }
            trial[idx] = 0;
            return best;
        }

        for (int use = 0; use <= max; use++) {
            long next = sum + (long) use * faces[idx];
            trial[idx] = use;
            best = search(counts, faces, order, depth + 1, amount, next, coinsSoFar + use,
                    trial, bestRef, best);
            // Once the prefix alone covers the amount, taking more of this
            // denomination can only overpay further.
            if (next >= amount) break;
        }
        trial[idx] = 0;
        return best;
    }

    /**
     * Greedy split of an amount into coins: largest denomination first. The result is
     * index-aligned with {@code faceValues}, which may be given in any order.
     */
    public static int[] makeChange(long amountMarks, int[] faceValues) {
        int[] out = new int[faceValues.length];
        if (amountMarks <= 0) return out;
        Integer[] order = new Integer[faceValues.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(faceValues[b], faceValues[a]));
        long remaining = amountMarks;
        for (int idx : order) {
            if (faceValues[idx] <= 0) continue;
            long n = remaining / faceValues[idx];
            if (n > Integer.MAX_VALUE) n = Integer.MAX_VALUE;
            out[idx] = (int) n;
            remaining -= n * faceValues[idx];
        }
        return out;
    }

    /**
     * Whether several kinds of stack can all be added to an inventory at once.
     *
     * <p>Kinds compete for the same empty slots, so adding up each kind's own free
     * capacity overstates the room. Each kind first fills the room left in its own
     * partial stacks, then needs whole empty slots for the rest.
     *
     * @param emptySlots completely empty slots
     * @param partialRoom per kind, how many more fit in stacks already holding that kind
     * @param needed     per kind, how many are to be added
     * @param maxStack   stack size, the same for every kind
     */
    public static boolean fitsAll(int emptySlots, long[] partialRoom, long[] needed, int maxStack) {
        if (maxStack <= 0) return false;
        long slots = 0;
        for (int i = 0; i < needed.length; i++) {
            long room = i < partialRoom.length ? Math.max(0, partialRoom[i]) : 0;
            long rest = needed[i] - room;
            if (rest > 0) slots += (rest + maxStack - 1) / maxStack;
        }
        return slots <= Math.max(0, emptySlots);
    }

    /**
     * Group separators for chat. {@code 12481} becomes {@code 12,481}.
     *
     * <p>Hand-rolled rather than {@link java.text.NumberFormat} so that a server
     * running under a locale using {@code .} as the separator does not render a
     * balance that reads like a decimal.
     */
    public static String format(long amount) {
        return group(amount);
    }

    /** Marks in one Gold Sovereign. */
    public static final long MARKS_PER_SOVEREIGN = 64;

    /**
     * Marks as Gold Sovereigns, the only unit a player sees (owner, 2026-11-04): whole
     * Sovereigns with group separators, {@code 2,261}, and a remainder as a decimal of at
     * most two places, {@code 2,261.5}. The decimal is cut, never rounded, so a balance is
     * never shown as more than it is: 63 Marks read {@code 0.98}, and 1 Mark {@code 0.01}.
     * A nonzero remainder never reads as {@code .00}. Marks stay the unit in code and files.
     */
    public static String sovereigns(long marks) {
        return inUnits(marks, MARKS_PER_SOVEREIGN);
    }

    /** {@link #sovereigns} for a coin of any face value: whole coins and a cut two place decimal. */
    public static String inUnits(long marks, long unit) {
        long abs = marks == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(marks);
        long whole = abs / unit;
        long hundredths = (abs % unit) * 100 / unit;
        String out = group(whole);
        if (hundredths > 0) {
            String frac = hundredths < 10 ? "0" + hundredths : Long.toString(hundredths);
            if (frac.endsWith("0")) frac = frac.substring(0, 1);
            out += "." + frac;
        }
        return marks < 0 ? "-" + out : out;
    }

    /** {@code 1 Sovereign}, {@code 0.5 Sovereigns}, {@code 2,261 Sovereigns}. */
    public static String sovereignWords(long marks) {
        return sovereigns(marks) + (marks == MARKS_PER_SOVEREIGN ? " Sovereign" : " Sovereigns");
    }

    private static String group(long amount) {
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
