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

    /** A drop a merchant accepts, and how many of it one emerald is worth. */
    record Currency(Item item, String name, double perEmerald) {}

    /**
     * @param title      the villager's name and the GUI title
     * @param currencies what the stock is priced in; empty means emeralds only
     */
    record Merchant(String title, List<Currency> currencies) {}

    static final Currency EMERALD = new Currency(Items.EMERALD, "emeralds", 1.0);

    static final Merchant WANDERING = new Merchant("Wandering Merchant", List.of());

    private static final Currency BONE = new Currency(Items.BONE, "bones", 2.0);
    private static final Currency ROTTEN_FLESH = new Currency(Items.ROTTEN_FLESH, "rotten flesh", 4.0);
    private static final Currency STRING = new Currency(Items.STRING, "string", 3.0);
    private static final Currency SPIDER_EYE = new Currency(Items.SPIDER_EYE, "spider eyes", 2.0);
    private static final Currency GUNPOWDER = new Currency(Items.GUNPOWDER, "gunpowder", 2.0);
    private static final Currency BLAZE_ROD = new Currency(Items.BLAZE_ROD, "blaze rods", 1.0);
    private static final Currency MAGMA_CREAM = new Currency(Items.MAGMA_CREAM, "magma cream", 1.0);
    private static final Currency ENDER_PEARL = new Currency(Items.ENDER_PEARL, "ender pearls", 0.5);

    private static final Map<String, Merchant> BY_THEME = Map.of(
            "ossuary", new Merchant("Bone Collector", List.of(BONE)),
            "deepslate", new Merchant("Bone Collector", List.of(BONE, ROTTEN_FLESH)),
            "endless_mine", new Merchant("Bone Collector", List.of(BONE, ROTTEN_FLESH)),
            "frostworks", new Merchant("Frost Peddler", List.of(BONE, ROTTEN_FLESH)),
            "basalt_foundry", new Merchant("Nether Merchant", List.of(BLAZE_ROD, MAGMA_CREAM)),
            "copper_works", new Merchant("Scrap Dealer", List.of(GUNPOWDER, ROTTEN_FLESH)),
            "ender_archive", new Merchant("Archivist", List.of(ENDER_PEARL)),
            "infestation", new Merchant("Web Trader", List.of(STRING, SPIDER_EYE)),
            "rootworks", new Merchant("Root Herbalist", List.of(STRING, SPIDER_EYE)));

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
