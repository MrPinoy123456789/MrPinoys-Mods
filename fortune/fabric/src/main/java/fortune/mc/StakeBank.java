package fortune.mc;

import net.fabricmc.loader.api.FabricLoader;

import java.util.UUID;

/**
 * Where the coin stake is charged: a player's banked Coin balance in Marks. The real bank is Charter Currency
 * ({@link CurrencyBank}); without it there is no bank and the stake buttons are hidden. Fortune never deposits Coin:
 * there is no method here that could.
 */
public interface StakeBank {

    /** True when stakes can be charged right now. */
    boolean available();

    /** Banked Marks, or 0 when unknown. */
    long balance(UUID player);

    /** Takes {@code marks} from the player's bank, all or nothing. True when it went through. */
    boolean charge(UUID player, String name, long marks);

    StakeBank NONE = new StakeBank() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public long balance(UUID player) {
            return 0;
        }

        @Override
        public boolean charge(UUID player, String name, long marks) {
            return false;
        }
    };

    /** Charter Currency's bank when that mod is loaded and usable, otherwise {@link #NONE}. Never throws. */
    static StakeBank detect() {
        if (!FabricLoader.getInstance().isModLoaded("chartercurrency")) return NONE;
        try {
            return new CurrencyBank();
        } catch (Throwable t) {
            FortuneMod.LOG.warn("Charter Currency is present but its API could not be used, so the coin stake is off: {}", t.toString());
            return NONE;
        }
    }
}
