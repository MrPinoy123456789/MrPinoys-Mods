package fortune.mc;

import chartercurrency.EconomyApi;
import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.EconomyService;
import fortune.core.Stake;

import java.util.UUID;

/**
 * The only class that touches Charter Currency. It is loaded only when that mod is present ({@link StakeBank#detect}),
 * so Fortune runs without it. The stake is a withdrawal from the player's banked Coin, logged here with the reason
 * {@code fortune_stake} (Charter Currency's own withdraw takes no reason) and in spins.jsonl.
 */
final class CurrencyBank implements StakeBank {

    CurrencyBank() {
        // Touch the class now so a broken Currency jar fails here, inside detect's catch, not mid spin.
        EconomyApi.class.getName();
    }

    @Override
    public boolean available() {
        return EconomyApi.economy() != null;
    }

    @Override
    public long balance(UUID player) {
        EconomyService economy = EconomyApi.economy();
        return economy == null ? 0 : economy.getBalance(player, coin());
    }

    @Override
    public boolean charge(UUID player, String name, long marks) {
        EconomyService economy = EconomyApi.economy();
        if (economy == null || marks <= 0) return false;
        boolean ok = economy.withdraw(player, coin(), marks).ok();
        if (ok) FortuneMod.LOG.info("[Economy] fortune_stake: {} ({}) staked {} at Fantasy's Fortune ({} Marks)", name, player,
                Stake.sovereignText(Stake.sovereigns(marks)), marks);
        return ok;
    }

    private static Currency coin() {
        CurrencyRegistry registry = EconomyApi.currencies();
        return registry != null ? registry.coin() : CurrencyRegistry.COIN;
    }
}
