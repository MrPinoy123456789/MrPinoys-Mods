package pocketdungeons;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

/**
 * Which merchant a floor's Store hosts, keyed by the floor's theme id, and
 * which of the floor's drops it buys for emeralds.
 *
 * <p>J4 (D36): stock is priced in emeralds only. The currencies below are
 * the buy list, not a pricing currency: each themed merchant takes the drops
 * of the mobs its floor spawns, so a surplus the player was going to step
 * over (bones, string, rotten flesh) has somewhere to go. A theme with no
 * entry (prismarine, blackstone, a third party theme) gets
 * {@link #WANDERING}, the original emerald-only merchant.
 *
 * <p>The drop list follows plan J4's set: string, bones, leather, spider
 * eyes, rotten flesh, slimeballs, blaze powder, magma cream, end stone.
 * Gunpowder and sand are never bought ({@link StorePricing#NEVER_BUY}): they
 * make TNT. Arrows are deliberately not bought either: they are scarce here,
 * and a merchant that eats them would punish the archer.
 */
final class MerchantThemes {

    /**
     * A drop a merchant accepts, how many of it one emerald is worth, and what a
     * merchant who buys it is called ({@code buyer}): the vendor is named for
     * what he takes, not for the floor he stands on (PD-142).
     */
    record Currency(Item item, String name, double perEmerald, String buyer) {}

    /**
     * @param title      the villager's name
     * @param currencies the drops it buys for emeralds; empty means it buys nothing
     */
    record Merchant(String title, List<Currency> currencies) {}

    static final Merchant WANDERING = new Merchant("Wandering Merchant", List.of());

    private static final Currency BONE = new Currency(Items.BONE, "bones", 2.0, "Bone Collector");
    private static final Currency ROTTEN_FLESH = new Currency(Items.ROTTEN_FLESH, "rotten flesh", 4.0, "Flesh Buyer");
    private static final Currency STRING = new Currency(Items.STRING, "string", 3.0, "Web Trader");
    private static final Currency SPIDER_EYE = new Currency(Items.SPIDER_EYE, "spider eyes", 2.0, "Eye Dealer");
    private static final Currency LEATHER = new Currency(Items.LEATHER, "leather", 2.0, "Hide Buyer");
    private static final Currency SLIME_BALL = new Currency(Items.SLIME_BALL, "slimeballs", 1.0, "Slime Broker");
    private static final Currency BLAZE_POWDER = new Currency(Items.BLAZE_POWDER, "blaze powder", 2.0, "Powder Trader");
    private static final Currency MAGMA_CREAM = new Currency(Items.MAGMA_CREAM, "magma cream", 1.0, "Cream Dealer");
    private static final Currency END_STONE = new Currency(Items.END_STONE, "end stone", 4.0, "End Broker");

    /** A merchant for {@code currencies}, named for the first thing it buys. */
    private static Merchant buying(Currency... currencies) {
        return new Merchant(currencies[0].buyer(), List.of(currencies));
    }

    private static final Map<String, Merchant> BY_THEME = Map.of(
            "ossuary", buying(BONE),
            "deepslate", buying(BONE, ROTTEN_FLESH),
            "endless_mine", buying(BONE, ROTTEN_FLESH),
            "frostworks", buying(BONE, ROTTEN_FLESH),
            "basalt_foundry", buying(BLAZE_POWDER, MAGMA_CREAM),
            "copper_works", buying(ROTTEN_FLESH),
            "ender_archive", buying(END_STONE),
            "infestation", buying(STRING, SPIDER_EYE),
            "rootworks", buying(STRING, SPIDER_EYE),
            "cow_pits", buying(LEATHER));

    /**
     * Every drop any merchant buys, in a fixed order: the home vendor's buy
     * list (J5a), offered at half the dungeon merchants' rate.
     */
    static final List<Currency> ALL_BUYS = List.of(BONE, ROTTEN_FLESH, STRING, SPIDER_EYE,
            LEATHER, SLIME_BALL, BLAZE_POWDER, MAGMA_CREAM, END_STONE);

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
}
