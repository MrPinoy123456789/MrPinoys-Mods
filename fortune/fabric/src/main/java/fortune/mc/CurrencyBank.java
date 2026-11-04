package fortune.mc;

import chartercurrency.EconomyApi;
import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.EconomyService;

import java.util.UUID;

/**
 * The only class that touches Charter Currency. It is loaded only when that mod is present ({@link StakeBank#detect}),
 * so Fortune runs without it. The stake is a withdrawal from the player's banked Coin through
 * {@code EconomyApi.chargeExternal}, which logs it with the reason {@code fortune_stake}; spins.jsonl records it too.
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
    public boolean charge(UUID player, String name, long marks, String reason) {
        if (EconomyApi.economy() == null || marks <= 0) return false;
        // Charter Currency writes the transactions.log line and the analytics event, and flushes the account file.
        return EconomyApi.chargeExternal(player, name, marks, reason).ok();
    }

    private static Currency coin() {
        CurrencyRegistry registry = EconomyApi.currencies();
        return registry != null ? registry.coin() : CurrencyRegistry.COIN;
    }
}
