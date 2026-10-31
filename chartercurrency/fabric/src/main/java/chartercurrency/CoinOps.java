package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import chartercurrency.core.Wallet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Moving physical coin in and out of an inventory, for callers whose money is not the
 * player's own bank (the kingdom treasury, a console {@code give}). The arithmetic is
 * {@link Wallet}; the rules are the bank's: coins are taken before anything is
 * credited, space is checked before anything is charged, and nothing is half done.
 */
final class CoinOps {

    private CoinOps() {}

    /** Coins of one denomination carried in the main 36 slots. */
    static long carried(ServerPlayer player, Denomination d) {
        return ItemBank.count(player, match(d));
    }

    static Predicate<ItemStack> match(Denomination d) {
        return stack -> CoinStacks.isCoin(stack, d);
    }

    /**
     * Plan a deposit of {@code amountMarks} (or every coin when {@code all}), optionally
     * restricted to one denomination taken as an exact count. Returns null with
     * {@code error} filled in when it cannot be done. Nothing is removed here.
     */
    static Wallet.DepositPlan plan(ServerPlayer player, Currency coin, boolean all, long amount,
                                   Denomination only, String[] error) {
        List<Denomination> denominations = coin.denominations();
        int[] faces = coin.faceValues();
        int[] counts = new int[faces.length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = (int) Math.min(Integer.MAX_VALUE, carried(player, denominations.get(i)));
        }
        if (all) {
            int[] usable = counts.clone();
            long total = 0;
            for (int i = 0; i < usable.length; i++) {
                if (only != null && !denominations.get(i).equals(only)) usable[i] = 0;
                total += (long) usable[i] * faces[i];
            }
            if (total <= 0) {
                error[0] = only == null ? "You are not carrying any coin."
                        : "You are not carrying any " + only.shortPlural() + ".";
                return null;
            }
            return Wallet.planDeposit(usable, faces, total);
        }
        if (only != null) {
            int idx = denominations.indexOf(only);
            if (amount > counts[idx]) {
                error[0] = "You only carry " + only.describe(counts[idx]) + ".";
                return null;
            }
            int[] take = new int[faces.length];
            take[idx] = (int) amount;
            long marks = amount * faces[idx];
            return new Wallet.DepositPlan(take, marks, 0, new int[faces.length]);
        }
        Wallet.DepositPlan plan = Wallet.planDeposit(counts, faces, amount);
        if (plan == null) {
            long marks = 0;
            for (int i = 0; i < counts.length; i++) marks += (long) counts[i] * faces[i];
            error[0] = "You only carry " + coin.describe(marks) + ".";
        }
        return plan;
    }

    /** Remove the planned coins, all or nothing; on failure everything taken goes back. */
    static boolean remove(ServerPlayer player, Currency coin, Wallet.DepositPlan plan) {
        List<Denomination> denominations = coin.denominations();
        List<int[]> removed = new ArrayList<>();
        for (int i = 0; i < plan.take().length; i++) {
            if (plan.take()[i] <= 0) continue;
            if (!ItemBank.removeExactly(player, match(denominations.get(i)), plan.take()[i])) {
                for (int[] r : removed) {
                    ItemBank.give(player, CoinStacks.make(denominations.get(r[0]), coin, 1), r[1]);
                }
                return false;
            }
            removed.add(new int[] {i, plan.take()[i]});
        }
        return true;
    }

    /** True if every kind of coin in {@code counts} fits at once. */
    static boolean fits(ServerPlayer player, Currency coin, int[] counts) {
        List<Denomination> denominations = coin.denominations();
        long[] room = new long[counts.length];
        long[] need = new long[counts.length];
        int maxStack = 64;
        for (int i = 0; i < counts.length; i++) {
            ItemStack proto = CoinStacks.make(denominations.get(i), coin, 1);
            room[i] = ItemBank.partialRoom(player, proto);
            need[i] = counts[i];
            maxStack = Math.min(maxStack, proto.getMaxStackSize());
        }
        return Wallet.fitsAll(ItemBank.emptySlots(player), room, need, maxStack);
    }

    /** Give coins; whatever cannot fit is dropped at the player's feet, never deleted. */
    static long give(ServerPlayer player, Currency coin, int[] counts) {
        long dropped = 0;
        List<Denomination> denominations = coin.denominations();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            dropped += ItemBank.give(player, CoinStacks.make(denominations.get(i), coin, 1), counts[i]);
        }
        return dropped;
    }
}
