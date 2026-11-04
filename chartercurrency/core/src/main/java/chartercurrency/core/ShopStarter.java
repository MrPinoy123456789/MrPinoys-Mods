package chartercurrency.core;

import java.util.List;

/**
 * The starter shop: what a fresh server lists, and the restock that brings an existing
 * shop up to it without undoing the owner's own prices.
 *
 * <p>Prices are in Marks (a Sovereign is 64). They were set on 2026-11-03 for the Mint's
 * 320 ingot soft cap, roughly four times the first starter prices, then raised by
 * {@link #PRICE_SCALE} the same day when one gold ingot became 10 Sovereigns. Every one is
 * a placeholder the owner will retune in {@code shop.json} or with
 * {@code /chartercurrency shop setprice}.
 *
 * <p>The one gold listing is the gold ingot at exactly what the Mint strikes from it
 * ({@link #GOLD_INGOT_MARKS}): the way back from coin to metal (owner, 2026-11-03), and
 * never a loop, since an ingot bought and struck again gives back the same 10 Sovereigns
 * with two of them the kingdom's. Nothing else gold is sold, nor anything that smelts back
 * into gold. Looting, Fortune and Mending books are left out on purpose (the first two
 * multiply gold farms).
 */
public final class ShopStarter {

    public static final String BUILDING = "Building Materials";
    public static final String WOOD = "Wood";
    public static final String LIGHT = "Lighting";
    public static final String DYES = "Dyes";
    public static final String GARDEN = "Garden";
    public static final String WORKING = "Working Items";
    public static final String BOOKS = "Enchanted Books";
    public static final String HONOURS = "Honours";
    public static final String GOLD = "Gold";

    private static final Currency COIN = CurrencyRegistry.COIN;
    private static final Currency VALOR = CurrencyRegistry.VALOR;

    /** Coin prices are this many times the one-Sovereign-an-ingot starter (owner, 2026-11-03). */
    public static final long PRICE_SCALE = 10;

    /** One gold ingot: the Sovereigns the Mint strikes from it, in Marks (10 times 64). */
    public static final long GOLD_INGOT_MARKS = (long) Mint.COINS_PER_STRIKE * 64;

    private ShopStarter() {}

    /** The current starter catalog. */
    public static ShopCatalog catalog() {
        ShopCatalog c = build(PRICE_SCALE);
        c.put(ShopEntry.of("gold_ingot", "minecraft:gold_ingot", 1, COIN, GOLD_INGOT_MARKS, GOLD));
        return c;
    }

    /** The starter as it shipped while one gold ingot struck one Sovereign. */
    public static ShopCatalog atOneSovereignAnIngot() {
        return build(1);
    }

    private static ShopCatalog build(long scale) {
        ShopCatalog c = new ShopCatalog();

        plain(c, BUILDING, 64, 128 * scale, "sand", "red_sand", "gravel", "white_wool");
        plain(c, BUILDING, 64, 192 * scale, "glass", "sandstone", "smooth_stone", "stone_bricks", "blackstone");
        plain(c, BUILDING, 64, 256 * scale, "ice", "terracotta", "calcite", "mud_bricks", "moss_block");
        c.put(ShopEntry.of("concrete_powder", "minecraft:white_concrete_powder", 64, COIN, 256 * scale, BUILDING));
        plain(c, BUILDING, 64, 320 * scale, "deepslate_bricks", "tuff_bricks");
        plain(c, BUILDING, 64, 384 * scale, "clay");
        plain(c, BUILDING, 64, 512 * scale, "prismarine");
        plain(c, BUILDING, 64, 640 * scale, "packed_ice", "end_stone");
        c.put(ShopEntry.of("quartz", "minecraft:quartz_block", 64, COIN, 768 * scale, BUILDING));
        plain(c, BUILDING, 64, 768 * scale, "dark_prismarine", "purpur_block");
        plain(c, BUILDING, 16, 1024 * scale, "blue_ice");

        plain(c, WOOD, 64, 128 * scale, "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
                "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log", "bamboo_block");
        plain(c, WOOD, 64, 192 * scale, "crimson_stem", "warped_stem");

        plain(c, LIGHT, 64, 512 * scale, "glowstone");
        plain(c, LIGHT, 16, 512 * scale, "sea_lantern");
        plain(c, LIGHT, 16, 384 * scale, "shroomlight");
        plain(c, LIGHT, 16, 256 * scale, "lantern");
        plain(c, LIGHT, 16, 128 * scale, "white_candle");

        plain(c, DYES, 16, 64 * scale, "white_dye", "light_gray_dye", "gray_dye", "black_dye", "brown_dye",
                "red_dye", "orange_dye", "yellow_dye", "lime_dye", "green_dye", "cyan_dye",
                "light_blue_dye", "blue_dye", "purple_dye", "magenta_dye", "pink_dye");

        plain(c, GARDEN, 8, 64 * scale, "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
                "acacia_sapling", "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling",
                "mangrove_propagule");
        plain(c, GARDEN, 16, 64 * scale, "cocoa_beans", "sugar_cane", "cactus", "bamboo", "kelp", "sweet_berries");
        plain(c, GARDEN, 16, 128 * scale, "glow_berries", "lily_pad");
        plain(c, GARDEN, 16, 192 * scale, "nether_wart");
        plain(c, GARDEN, 64, 128 * scale, "bone_meal");

        c.put(ShopEntry.of("rockets", "minecraft:firework_rocket", 64, COIN, 512 * scale, WORKING));
        plain(c, WORKING, 1, 256 * scale, "name_tag");
        plain(c, WORKING, 1, 512 * scale, "saddle");
        plain(c, WORKING, 4, 128 * scale, "lead", "armor_stand");
        plain(c, WORKING, 64, 128 * scale, "string");
        plain(c, WORKING, 16, 256 * scale, "slime_ball");
        plain(c, WORKING, 1, 128 * scale, "spyglass", "compass");
        plain(c, WORKING, 1, 64 * scale, "map");
        plain(c, WORKING, 16, 128 * scale, "item_frame");
        plain(c, WORKING, 16, 64 * scale, "flower_pot");
        plain(c, WORKING, 4, 64 * scale, "painting");
        plain(c, WORKING, 16, 512 * scale, "bookshelf");

        book(c, "book_unbreaking", "unbreaking", 3, 1024 * scale);
        book(c, "book_efficiency", "efficiency", 4, 1280 * scale);
        book(c, "book_protection", "protection", 4, 1280 * scale);
        book(c, "book_sharpness", "sharpness", 5, 1280 * scale);
        book(c, "book_power", "power", 5, 1280 * scale);
        book(c, "book_feather_falling", "feather_falling", 4, 768 * scale);
        book(c, "book_silk_touch", "silk_touch", 1, 768 * scale);
        book(c, "book_depth_strider", "depth_strider", 3, 768 * scale);
        book(c, "book_respiration", "respiration", 3, 512 * scale);
        book(c, "book_aqua_affinity", "aqua_affinity", 1, 384 * scale);

        honours(c);
        return c;
    }

    /** The first starter catalog, as written to every shop.json before 2026-11-03. */
    public static ShopCatalog previous() {
        ShopCatalog c = new ShopCatalog();
        String building = BUILDING;
        String working = WORKING;
        c.put(ShopEntry.of("ice", "minecraft:ice", 64, COIN, 64, building));
        c.put(ShopEntry.of("sand", "minecraft:sand", 64, COIN, 32, building));
        c.put(ShopEntry.of("glass", "minecraft:glass", 64, COIN, 64, building));
        c.put(ShopEntry.of("terracotta", "minecraft:terracotta", 64, COIN, 96, building));
        c.put(ShopEntry.of("quartz", "minecraft:quartz_block", 64, COIN, 192, building));
        c.put(ShopEntry.of("concrete_powder", "minecraft:white_concrete_powder", 64, COIN, 96, building));
        c.put(ShopEntry.of("rockets", "minecraft:firework_rocket", 64, COIN, 128, working));
        c.put(ShopEntry.of("book_unbreaking", "minecraft:enchanted_book", 1, COIN, 192, working,
                storedEnchantment("unbreaking", 3)));
        c.put(ShopEntry.of("book_efficiency", "minecraft:enchanted_book", 1, COIN, 256, working,
                storedEnchantment("efficiency", 4)));
        honours(c);
        return c;
    }

    /** What {@link #restock} did. */
    public record Restock(int added, int updated, int kept) {}

    /**
     * Bring a live catalog up to the current starter, in place.
     *
     * <ul>
     *   <li>A starter listing whose key is missing is added.</li>
     *   <li>A listing still exactly as an earlier starter shipped it (the first one, or
     *       the one priced for one Sovereign an ingot: price, quantity, item, components
     *       and category) is replaced by the current one, keeping its enabled flag. The
     *       owner never touched it.</li>
     *   <li>Anything the owner changed, and every listing that is not a starter one, is
     *       left alone. Nothing is ever removed.</li>
     * </ul>
     */
    public static Restock restock(ShopCatalog live) {
        List<ShopCatalog> earlier = List.of(previous(), atOneSovereignAnIngot());
        int added = 0;
        int updated = 0;
        int kept = 0;
        for (ShopEntry fresh : catalog().all()) {
            ShopEntry current = live.find(fresh.key()).orElse(null);
            if (current == null) {
                live.put(fresh);
                added++;
            } else if (sameListing(current, fresh)) {
                // already current
            } else if (earlier.stream().anyMatch(old -> old.find(fresh.key())
                    .filter(was -> sameListing(current, was)).isPresent())) {
                live.put(new ShopEntry(fresh.key(), fresh.itemId(), fresh.quantity(), fresh.price(),
                        fresh.category(), current.enabled(), fresh.components()));
                updated++;
            } else {
                kept++;
            }
        }
        return new Restock(added, updated, kept);
    }

    /** Same goods at the same price on the same shelf; the enabled flag is not compared. */
    static boolean sameListing(ShopEntry a, ShopEntry b) {
        return a.itemId().equals(b.itemId()) && a.quantity() == b.quantity()
                && a.price().equals(b.price()) && a.category().equals(b.category())
                && java.util.Objects.equals(blankToNull(a.components()), blankToNull(b.components()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void plain(ShopCatalog c, String category, int quantity, long marks, String... items) {
        for (String item : items) {
            c.put(ShopEntry.of(item, "minecraft:" + item, quantity, COIN, marks, category));
        }
    }

    private static void book(ShopCatalog c, String key, String enchantment, int level, long marks) {
        c.put(ShopEntry.of(key, "minecraft:enchanted_book", 1, COIN, marks, BOOKS,
                storedEnchantment(enchantment, level)));
    }

    private static String storedEnchantment(String enchantment, int level) {
        return "{\"minecraft:stored_enchantments\":{\"minecraft:" + enchantment + "\":" + level + "}}";
    }

    private static void honours(ShopCatalog c) {
        c.put(ShopEntry.of("champion_helm", "minecraft:iron_helmet", 1, VALOR, 150, HONOURS,
                "{\"minecraft:item_name\":\"Champion's Helm\","
                        + "\"minecraft:trim\":{\"material\":\"minecraft:gold\",\"pattern\":\"minecraft:silence\"}}"));
        c.put(ShopEntry.of("champion_head", "minecraft:player_head", 1, VALOR, 80, HONOURS,
                "{\"minecraft:item_name\":\"Champion's Trophy\"}"));
        c.put(ShopEntry.of("valor_banner", "minecraft:white_banner", 1, VALOR, 40, HONOURS,
                "{\"minecraft:item_name\":\"Banner of Valor\"}"));
    }
}
