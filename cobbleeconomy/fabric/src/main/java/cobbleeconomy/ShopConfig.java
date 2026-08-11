package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.CurrencyRegistry;
import cobbleeconomy.core.ShopCatalog;
import cobbleeconomy.core.ShopEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code config/cobbleeconomy/shop.json}. Prices live here, never in Java.
 *
 * <p>Section 23 of the shop spec is the reason: {@code 64 Ice = 640 Cobblestone} is a
 * decision the server owner makes, not a number derived from vanilla rarity, and they
 * will retune it repeatedly once real players touch it. Anything that requires a
 * rebuild to change is a price nobody will change.
 *
 * <p>Two price forms are accepted. The flat one matches the spec exactly:
 *
 * <pre>
 *   "ice": { "item": "minecraft:ice", "quantity": 64,
 *            "price": 640, "currency": "cobblestone" }
 * </pre>
 *
 * and the compound one, for when an entry should cost two currencies at once:
 *
 * <pre>
 *   "elytra": { "item": "minecraft:elytra", "quantity": 1,
 *               "price": { "cobblestone": 500, "diamond": 1 } }
 * </pre>
 *
 * <p>Both land in the same {@link ShopEntry}, whose price is always a map. Writing the
 * reader to accept the compound form now costs a dozen lines; adding it later would
 * mean migrating every server owner's file.
 */
public final class ShopConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final CurrencyRegistry currencies;

    public ShopConfig(Path directory, CurrencyRegistry currencies) {
        this.file = directory.resolve("shop.json");
        this.currencies = currencies;
    }

    /**
     * Read the catalog, writing a starter file first if none exists.
     *
     * @param registries needed to parse the optional {@code components} block of a
     *                   listing, so a bad one is caught here as a startup warning
     *                   rather than at the moment a player spends money on it
     */
    public ShopCatalog load(HolderLookup.Provider registries) {
        ShopCatalog catalog = new ShopCatalog();
        if (!Files.exists(file)) {
            writeDefaults();
        }
        if (!Files.exists(file)) return catalog;

        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
            JsonObject shop = root.getAsJsonObject("shop");
            if (shop == null) return catalog;

            int rejected = 0;
            for (Map.Entry<String, JsonElement> e : shop.entrySet()) {
                ShopEntry entry = readEntry(e.getKey(), e.getValue(), registries);
                if (entry == null || !entry.isValid()) {
                    rejected++;
                    CobbleEconomyMod.LOG.warn("Shop entry '{}' is malformed and was skipped", e.getKey());
                    continue;
                }
                catalog.put(entry);
            }
            CobbleEconomyMod.LOG.info("Loaded {} shop entries ({} rejected)",
                    catalog.size(), rejected);
        } catch (Exception e) {
            // Unlike accounts.json, a bad shop file costs nobody their money -- the
            // shop simply has nothing in it. Starting with an empty shop beats
            // refusing to start the server over a misplaced comma in a price list.
            CobbleEconomyMod.LOG.error("Could not read shop.json; the shop will be empty", e);
        }
        return catalog;
    }

    private ShopEntry readEntry(String key, JsonElement element,
                                HolderLookup.Provider registries) {
        try {
            JsonObject object = element.getAsJsonObject();
            String itemId = object.get("item").getAsString();
            int quantity = object.get("quantity").getAsInt();
            String category = object.has("category")
                    ? object.get("category").getAsString() : "Goods";
            boolean enabled = !object.has("enabled") || object.get("enabled").getAsBoolean();

            Map<Currency, Long> price = new LinkedHashMap<>();
            JsonElement priceElement = object.get("price");
            if (priceElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> line : priceElement.getAsJsonObject().entrySet()) {
                    Currency currency = currencies.byId(line.getKey()).orElse(null);
                    if (currency == null) {
                        CobbleEconomyMod.LOG.warn("Shop entry '{}' names unknown currency '{}'",
                                key, line.getKey());
                        return null;
                    }
                    price.put(currency, line.getValue().getAsLong());
                }
            } else {
                String currencyId = object.has("currency")
                        ? object.get("currency").getAsString() : CurrencyRegistry.COBBLESTONE.id();
                Currency currency = currencies.byId(currencyId).orElse(null);
                if (currency == null) {
                    CobbleEconomyMod.LOG.warn("Shop entry '{}' names unknown currency '{}'",
                            key, currencyId);
                    return null;
                }
                price.put(currency, priceElement.getAsLong());
            }

            // The item is checked here rather than at purchase time, so a typo shows
            // up as one startup warning instead of a confused player whose money
            // vanished into an item that does not exist.
            if (WondrousShop.isWondrousItemId(itemId)) {
                String wondrousId = WondrousShop.idFrom(itemId);
                if (!WondrousShop.available() || WondrousShop.baseItem(wondrousId).isEmpty()) {
                    CobbleEconomyMod.LOG.warn(
                            "Shop entry '{}' names wondrous item '{}', but it could not be "
                                    + "resolved (mod absent or unknown id)", key, itemId);
                    return null;
                }
            } else if (ItemBank.resolve(itemId).isEmpty()) {
                CobbleEconomyMod.LOG.warn("Shop entry '{}' names unknown item '{}'", key, itemId);
                return null;
            }

            // Optional. Checked here for the same reason the item id is: a listing that
            // sells a component-marked item is worthless if the components are wrong,
            // and one startup warning beats a player paying for a plain echo shard.
            String components = null;
            if (object.has("components")) {
                components = object.get("components").toString();
                if (ItemComponents.parse(components, registries).isEmpty()) {
                    CobbleEconomyMod.LOG.warn(
                            "Shop entry '{}' has a components block that could not be parsed", key);
                    return null;
                }
            }

            return new ShopEntry(key, itemId, quantity, price, category, enabled, components);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Persist the current catalog, so admin edits survive a restart. */
    public synchronized void save(ShopCatalog catalog) {
        JsonObject shop = new JsonObject();
        for (ShopEntry entry : catalog.all()) {
            JsonObject object = new JsonObject();
            object.addProperty("item", entry.itemId());
            object.addProperty("quantity", entry.quantity());

            if (entry.price().size() == 1) {
                Map.Entry<Currency, Long> only = entry.price().entrySet().iterator().next();
                object.addProperty("price", only.getValue());
                object.addProperty("currency", only.getKey().id());
            } else {
                JsonObject price = new JsonObject();
                for (Map.Entry<Currency, Long> line : entry.price().entrySet()) {
                    price.addProperty(line.getKey().id(), line.getValue());
                }
                object.add("price", price);
            }
            object.addProperty("category", entry.category());
            if (!entry.enabled()) object.addProperty("enabled", false);
            // Must survive a rewrite. Every admin shop command rebuilds the entry and
            // calls save(); without this, one /cobbleeconomy shop setprice would strip
            // the components off a listing and start selling a plain item.
            if (entry.hasComponents()) {
                object.add("components", JsonParser.parseString(entry.components()));
            }
            shop.add(entry.key(), object);
        }

        JsonObject root = new JsonObject();
        root.add("shop", shop);

        Path temp = file.resolveSibling("shop.json.tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, out);
                out.flush();
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CobbleEconomyMod.LOG.error("Failed to write shop.json", e);
        }
    }

    /**
     * The worked example from the spec, so a fresh server has a shop to look at and a
     * file to copy the shape from. Every one of these prices is a guess and is meant
     * to be changed.
     */
    private void writeDefaults() {
        ShopCatalog catalog = new ShopCatalog();
        Currency cobble = CurrencyRegistry.COBBLESTONE;
        Currency diamond = CurrencyRegistry.DIAMOND;
        String building = "Building Materials";
        String rare = "Rare Resources";

        catalog.put(ShopEntry.of("ice", "minecraft:ice", 64, cobble, 640, building));
        catalog.put(ShopEntry.of("snow", "minecraft:snow_block", 64, cobble, 320, building));
        catalog.put(ShopEntry.of("packed_ice", "minecraft:packed_ice", 64, cobble, 1_280, building));
        catalog.put(ShopEntry.of("dirt", "minecraft:dirt", 64, cobble, 160, building));
        catalog.put(ShopEntry.of("sand", "minecraft:sand", 64, cobble, 320, building));
        catalog.put(ShopEntry.of("gravel", "minecraft:gravel", 64, cobble, 320, building));
        catalog.put(ShopEntry.of("ancient_debris", "minecraft:ancient_debris", 1, diamond, 2, rare));
        catalog.put(ShopEntry.of("netherite_upgrade", "minecraft:netherite_upgrade_smithing_template",
                1, diamond, 8, rare));
        catalog.put(ShopEntry.of("elytra", "minecraft:elytra", 1, diamond, 20, rare));
        // Sample wondrous listing -- resolved through WondrousShop, not the item
        // registry. If the wondrous mod isn't installed, this entry is skipped with
        // one log warning at the next reload; it does not stop the server starting.
        catalog.put(ShopEntry.of("big_hole_shovel", "wondrous:big_hole_shovel", 1, diamond, 12, rare));

        save(catalog);
        CobbleEconomyMod.LOG.info("Wrote a starter shop.json -- edit it to set your own prices");
    }
}
