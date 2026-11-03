package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import chartercurrency.core.TxResult;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Right-click a coin stack to bank it: the whole stack in the main hand goes into the
 * player's account, the same money {@code /bank} would credit. The coin lore says so
 * ({@link CoinStacks#make}).
 *
 * <p>The order is the bank's: the stack is taken, then credited, and handed back
 * untouched if the bank refuses. A stack is one denomination, so there is never change.
 * A click on a block with a use of its own (a chest, a bound Mint table) reaches that
 * block first; this only answers an item use that nothing else took.
 */
final class CoinBankClick {

    private final Services s;

    CoinBankClick(Services services) {
        this.s = services;
    }

    void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            Optional<Denomination> d = denominationOf(sp.getMainHandItem());
            if (d.isEmpty()) return InteractionResult.PASS;
            bank(sp, d.get());
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    private Optional<Denomination> denominationOf(ItemStack stack) {
        Optional<String> marker = CoinStacks.markerOf(stack);
        if (marker.isEmpty()) return Optional.empty();
        for (Denomination d : s.coin().denominations()) {
            if (d.id().equals(marker.get())) return Optional.of(d);
        }
        return Optional.empty();
    }

    private void bank(ServerPlayer player, Denomination d) {
        Currency coin = s.coin();
        ItemStack stack = player.getMainHandItem();
        int count = stack.getCount();
        long marks = (long) count * d.value();

        ItemStack taken = stack.copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        TxResult result = s.economy.deposit(player.getUUID(), coin, marks);
        if (!result.ok()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, taken);
            player.sendSystemMessage(Messages.bad("Could not bank that. Nothing was changed."));
            return;
        }

        Chime.purchased(player);
        player.sendSystemMessage(Messages.good("Banked ")
                .append(ShopDisplay.coins(coin, marks))
                .append(Messages.good(". In the bank: "))
                .append(ShopDisplay.coins(coin, result.actorBalance()))
                .append(Messages.good(".")));
        s.log.tx(player.getName().getString() + " deposited " + marks + " coin by right-click ("
                + count + " " + d.id() + ") (balance " + result.actorBalance() + ")");
    }
}
