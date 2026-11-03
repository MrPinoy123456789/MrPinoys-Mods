package chartercurrency.core;

/**
 * The starter shop: what a fresh server lists, and the restock that brings an existing
 * shop up to it without undoing the owner's own prices.
 *
 * <p>Prices are in Marks (a Sovereign is 64, one gold ingot at the Mint). They were set on
 * 2026-11-03 for the Mint's 320 ingot soft cap, roughly four times the first starter
 * prices, and every one is a placeholder the owner will retune in {@code shop.json} or
 * with {@code /chartercurrency shop setprice}.
 *
 * <p>Nothing here sells gold in any form, or anything that smelts back into gold: bought
 * gold could be struck at the Mint, and the shop would become a coin loop. Looting,
 * Fortune and Mending books are left out on purpose (the first two multiply gold farms).
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

    private static final Currency COIN = CurrencyRegistry.COIN;
    private static final Currency VALOR = CurrencyRegistry.VALOR;

    private ShopStarter() {}

    /** The current starter catalog. */
    public static ShopCatalog catalog() {
        ShopCatalog c = new ShopCatalog();

        plain(c, BUILDING, 64, 128, "sand", "red_sand", "gravel", "white_wool");
        plain(c, BUILDING, 64, 192, "glass", "sandstone", "smooth_stone", "stone_bricks", "blackstone");
        plain(c, BUILDING, 64, 256, "ice", "terracotta", "calcite", "mud_bricks", "moss_block");
        c.put(ShopEntry.of("concrete_powder", "minecraft:white_concrete_powder", 64, COIN, 256, BUILDING));
        plain(c, BUILDING, 64, 320, "deepslate_bricks", "tuff_bricks");
        plain(c, BUILDING, 64, 384, "clay");
        plain(c, BUILDING, 64, 512, "prismarine");
        plain(c, BUILDING, 64, 640, "packed_ice", "end_stone");
        c.put(ShopEntry.of("quartz", "minecraft:quartz_block", 64, COIN, 768, BUILDING));
        plain(c, BUILDING, 64, 768, "dark_prismarine", "purpur_block");
        plain(c, BUILDING, 16, 1024, "blue_ice");

        plain(c, WOOD, 64, 128, "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
                "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log", "bamboo_block");
        plain(c, WOOD, 64, 192, "crimson_stem", "warped_stem");

        plain(c, LIGHT, 64, 512, "glowstone");
        plain(c, LIGHT, 16, 512, "sea_lantern");
        plain(c, LIGHT, 16, 384, "shroomlight");
        plain(c, LIGHT, 16, 256, "lantern");
        plain(c, LIGHT, 16, 128, "white_candle");

        plain(c, DYES, 16, 64, "white_dye", "light_gray_dye", "gray_dye", "black_dye", "brown_dye",
                "red_dye", "orange_dye", "yellow_dye", "lime_dye", "green_dye", "cyan_dye",
                "light_blue_dye", "blue_dye", "purple_dye", "magenta_dye", "pink_dye");

        plain(c, GARDEN, 8, 64, "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
                "acacia_sapling", "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling",
                "mangrove_propagule");
        plain(c, GARDEN, 16, 64, "cocoa_beans", "sugar_cane", "cactus", "bamboo", "kelp", "sweet_berries");
        plain(c, GARDEN, 16, 128, "glow_berries", "lily_pad");
        plain(c, GARDEN, 16, 192, "nether_wart");
        plain(c, GARDEN, 64, 128, "bone_meal");

        c.put(ShopEntry.of("rockets", "minecraft:firework_rocket", 64, COIN, 512, WORKING));
        plain(c, WORKING, 1, 256, "name_tag");
        plain(c, WORKING, 1, 512, "saddle");
        plain(c, WORKING, 4, 128, "lead", "armor_stand");
        plain(c, WORKING, 64, 128, "string");
        plain(c, WORKING, 16, 256, "slime_ball");
        plain(c, WORKING, 1, 128, "spyglass", "compass");
        plain(c, WORKING, 1, 64, "map");
        plain(c, WORKING, 16, 128, "item_frame");
        plain(c, WORKING, 16, 64, "flower_pot");
        plain(c, WORKING, 4, 64, "painting");
        plain(c, WORKING, 16, 512, "bookshelf");

        book(c, "book_unbreaking", "unbreaking", 3, 1024);
        book(c, "book_efficiency", "efficiency", 4, 1280);
        book(c, "book_protection", "protection", 4, 1280);
        book(c, "book_sharpness", "sharpness", 5, 1280);
        book(c, "book_power", "power", 5, 1280);
        book(c, "book_feather_falling", "feather_falling", 4, 768);
        book(c, "book_silk_touch", "silk_touch", 1, 768);
        book(c, "book_depth_strider", "depth_strider", 3, 768);
        book(c, "book_respiration", "respiration", 3, 512);
        book(c, "book_aqua_affinity", "aqua_affinity", 1, 384);

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
     *   <li>A listing still exactly as the previous starter shipped it (price, quantity,
     *       item, components and category) is replaced by the current one, keeping its
     *       enabled flag. The owner never touched it.</li>
     *   <li>Anything the owner changed, and every listing that is not a starter one, is
     *       left alone. Nothing is ever removed.</li>
     * </ul>
     */
    public static Restock restock(ShopCatalog live) {
        ShopCatalog previous = previous();
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
            } else if (previous.find(fresh.key()).filter(old -> sameListing(current, old)).isPresent()) {
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
