package pocketdungeons;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

/**
 * Which merchant a floor's Store hosts, keyed by the floor's theme id.
 *
 * <p>The theme is the floor identity the game already has: its
 * {@code spawner_prefix} decides which mobs fill the floor's trial spawners,
 * so the table below is just that mapping read in the other direction. Each
 * merchant takes the drops of the mobs its floor spawns, so a surplus the
 * player was going to step over (bones, string, rotten flesh) has somewhere to
 * go. A theme with no entry (prismarine, blackstone, a third party theme) gets
 * {@link #WANDERING}, the original emerald-only merchant.
 *
 * <p>Arrows are deliberately not a currency: they are scarce here, and a
 * merchant that eats them would punish the archer.
 */
final class MerchantThemes {

    /**
     * A drop a merchant accepts, how many of it one emerald is worth, and what a
     * merchant who buys it is called ({@code buyer}): the vendor is named for
     * what he takes, not for the floor he stands on (PD-142).
     */
    record Currency(Item item, String name, double perEmerald, String buyer) {}

    /**
     * @param title      the villager's name and the GUI title
     * @param currencies what the stock is priced in; empty means emeralds only
     */
    record Merchant(String title, List<Currency> currencies) {}

    static final Currency EMERALD = new Currency(Items.EMERALD, "emeralds", 1.0, "Wandering Merchant");

    static final Merchant WANDERING = new Merchant("Wandering Merchant", List.of());

    private static final Currency BONE = new Currency(Items.BONE, "bones", 2.0, "Bone Collector");
    private static final Currency ROTTEN_FLESH = new Currency(Items.ROTTEN_FLESH, "rotten flesh", 4.0, "Flesh Buyer");
    private static final Currency STRING = new Currency(Items.STRING, "string", 3.0, "Web Trader");
    private static final Currency SPIDER_EYE = new Currency(Items.SPIDER_EYE, "spider eyes", 2.0, "Eye Dealer");
    private static final Currency GUNPOWDER = new Currency(Items.GUNPOWDER, "gunpowder", 2.0, "Powder Dealer");
    private static final Currency BLAZE_ROD = new Currency(Items.BLAZE_ROD, "blaze rods", 1.0, "Blaze Trader");
    private static final Currency MAGMA_CREAM = new Currency(Items.MAGMA_CREAM, "magma cream", 1.0, "Cream Dealer");
    private static final Currency ENDER_PEARL = new Currency(Items.ENDER_PEARL, "ender pearls", 0.5, "Pearl Broker");

    /** A merchant for {@code currencies}, named for the first thing he buys. */
    private static Merchant buying(Currency... currencies) {
        return new Merchant(currencies[0].buyer(), List.of(currencies));
    }

    private static final Map<String, Merchant> BY_THEME = Map.of(
            "ossuary", buying(BONE),
            "deepslate", buying(BONE, ROTTEN_FLESH),
            "endless_mine", buying(BONE, ROTTEN_FLESH),
            "frostworks", buying(BONE, ROTTEN_FLESH),
            "basalt_foundry", buying(BLAZE_ROD, MAGMA_CREAM),
            "copper_works", buying(GUNPOWDER, ROTTEN_FLESH),
            "ender_archive", buying(ENDER_PEARL),
            "infestation", buying(STRING, SPIDER_EYE),
            "rootworks", buying(STRING, SPIDER_EYE));

    private MerchantThemes() {}

    /** The merchant for {@code themeId} (namespaced or bare), or {@link #WANDERING} for none. */
    static Merchant forTheme(String themeId) {
        if (themeId == null) {
            return WANDERING;
        }
        int colon = themeId.indexOf(':');
        String path = colon >= 0 ? themeId.substring(colon + 1) : themeId;
        return BY_THEME.getOrDefault(path, WANDERING);
    }

    /** Looks a currency up by item, for reading a saved inventory back. Emerald when unknown. */
    static Currency byItem(Item item) {
        for (Merchant merchant : BY_THEME.values()) {
            for (Currency currency : merchant.currencies()) {
                if (currency.item() == item) {
                    return currency;
                }
            }
        }
        return EMERALD;
    }
}
