package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.ShopCatalog;
import chartercurrency.core.ShopEntry;
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
 * {@code config/chartercurrency/shop.json}. Prices live here, never in Java.
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

    /**
     * Entries from the last load that could not be read, keyed by their shop key, held
     * as the raw JSON that was on disk. Rewritten untouched by {@link #save}, so a
     * listing whose mod is absent survives an admin edit instead of being silently
     * deleted from the file.
     */
    private volatile Map<String, JsonElement> preserved = Map.of();

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
            Map<String, JsonElement> unreadable = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : shop.entrySet()) {
                ShopEntry entry = readEntry(e.getKey(), e.getValue(), registries);
                if (entry == null || !entry.isValid()) {
                    rejected++;
                    unreadable.put(e.getKey(), e.getValue());
                    CharterCurrencyMod.LOG.warn("Shop entry '{}' is malformed and was skipped", e.getKey());
                    continue;
                }
                catalog.put(entry);
            }
            // Held so save() can write them back untouched. See the field's comment: a
            // rejected entry is not in the catalog, and save() writes only the catalog.
            this.preserved = Map.copyOf(unreadable);
            CharterCurrencyMod.LOG.info("Loaded {} shop entries ({} rejected)",
                    catalog.size(), rejected);
        } catch (Exception e) {
            // Unlike accounts.json, a bad shop file costs nobody their money -- the
            // shop simply has nothing in it. Starting with an empty shop beats
            // refusing to start the server over a misplaced comma in a price list.
            CharterCurrencyMod.LOG.error("Could not read shop.json; the shop will be empty", e);
        }
        return catalog;
    }

    private ShopEntry readEntry(String key, JsonElement element,
                                HolderLookup.Provider registries) {
        try {
            JsonObject object = element.getAsJsonObject();
            String itemId;
            boolean isSuiteItem = object.has("suite_item");
            if (object.has("item") && isSuiteItem) {
                CharterCurrencyMod.LOG.warn("Shop entry '{}' has both 'item' and 'suite_item'; pick one", key);
                return null;
            }
            if (isSuiteItem) {
                String suiteId = object.get("suite_item").getAsString();
                itemId = SuiteItems.PREFIX + suiteId;
                if (SuiteItems.baseItem(suiteId).isEmpty()) {
                    CharterCurrencyMod.LOG.warn(
                            "Shop entry '{}' names suite item '{}', but it could not be resolved", key, itemId);
                    return null;
                }
            } else if (object.has("item")) {
                itemId = object.get("item").getAsString();
            } else {
                CharterCurrencyMod.LOG.warn("Shop entry '{}' has neither 'item' nor 'suite_item'", key);
                return null;
            }
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
                        CharterCurrencyMod.LOG.warn("Shop entry '{}' names unknown currency '{}'",
                                key, line.getKey());
                        return null;
                    }
                    price.put(currency, line.getValue().getAsLong());
                }
            } else {
                String currencyId = object.has("currency")
                        ? object.get("currency").getAsString() : CurrencyRegistry.COIN.id();
                Currency currency = currencies.byId(currencyId).orElse(null);
                if (currency == null) {
                    CharterCurrencyMod.LOG.warn("Shop entry '{}' names unknown currency '{}'",
                            key, currencyId);
                    return null;
                }
                price.put(currency, priceElement.getAsLong());
            }

            // The item is checked here rather than at purchase time, so a typo shows
            // up as one startup warning instead of a confused player whose money
            // vanished into an item that does not exist.
            //
            // A suite item was already validated via SuiteItems.baseItem() above --
            // itemId here is the internal "suite:<namespace>:<path>" marker, not a
            // registry id or a "wondrous:" id, so it must not be re-checked against
            // either lookup below.
            if (!isSuiteItem) {
                if (ItemBank.resolve(itemId).isEmpty()) {
                    CharterCurrencyMod.LOG.warn("Shop entry '{}' names unknown item '{}'", key, itemId);
                    return null;
                }
            }

            // Optional. Checked here for the same reason the item id is: a listing that
            // sells a component-marked item is worthless if the components are wrong,
            // and one startup warning beats a player paying for a plain echo shard.
            String components = null;
            if (object.has("components")) {
                components = object.get("components").toString();
                if (ItemComponents.parse(components, registries).isEmpty()) {
                    CharterCurrencyMod.LOG.warn(
                            "Shop entry '{}' has a components block that could not be parsed", key);
                    return null;
                }
            }

            return new ShopEntry(key, itemId, quantity, price, category, enabled, components);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Persist the current catalog, so admin edits survive a restart.
     *
     * <p><b>Entries that failed to load are written back verbatim.</b> save() writes the
     * catalog, and a rejected entry is not in the catalog -- so without {@link #preserved}
     * the first admin edit after a failed load deletes every rejected listing from the
     * file. That is not hypothetical: it is the load-order bug DESIGN.md §3 records, where
     * the shop loaded before wondrous initialised, dropped every {@code wondrous:} entry,
     * and rewrote shop.json without them. A suite listing whose mod is temporarily absent
     * has exactly the same exposure, and SUITE_ITEMS.md §8.1 promises the opposite: hidden
     * and logged, never deleted, because uninstalling a mod for an evening must not
     * destroy an hour of price tuning.
     */
    public synchronized void save(ShopCatalog catalog) {
        JsonObject shop = new JsonObject();
        for (ShopEntry entry : catalog.all()) {
            JsonObject object = new JsonObject();
            if (SuiteItems.isSuiteItemId(entry.itemId())) {
                object.addProperty("suite_item", SuiteItems.idFrom(entry.itemId()));
            } else {
                object.addProperty("item", entry.itemId());
            }
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
            // calls save(); without this, one /chartercurrency shop setprice would strip
            // the components off a listing and start selling a plain item.
            if (entry.hasComponents()) {
                object.add("components", JsonParser.parseString(entry.components()));
            }
            shop.add(entry.key(), object);
        }

        // Verbatim, and only for keys the catalog does not now define -- if an admin has
        // since created a listing under the same key, theirs is the live one and wins.
        for (Map.Entry<String, JsonElement> held : preserved.entrySet()) {
            if (!shop.has(held.getKey())) shop.add(held.getKey(), held.getValue());
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
            CharterCurrencyMod.LOG.error("Failed to write shop.json", e);
        }
    }

    /**
     * The worked example from the spec, so a fresh server has a shop to look at and a
     * file to copy the shape from. Every one of these prices is a guess and is meant
     * to be changed.
     */
    private void writeDefaults() {
        ShopCatalog catalog = new ShopCatalog();
        Currency coin = CurrencyRegistry.COIN;
        Currency valor = CurrencyRegistry.VALOR;
        String building = "Building Materials";
        String working = "Working Items";
        String honours = "Honours";

        // Every price is a placeholder the owner will retune. Prices are in Marks.
        catalog.put(ShopEntry.of("ice", "minecraft:ice", 64, coin, 64, building));
        catalog.put(ShopEntry.of("sand", "minecraft:sand", 64, coin, 32, building));
        catalog.put(ShopEntry.of("glass", "minecraft:glass", 64, coin, 64, building));
        catalog.put(ShopEntry.of("terracotta", "minecraft:terracotta", 64, coin, 96, building));
        catalog.put(ShopEntry.of("quartz", "minecraft:quartz_block", 64, coin, 192, building));
        catalog.put(ShopEntry.of("concrete_powder", "minecraft:white_concrete_powder", 64, coin, 96, building));
        catalog.put(ShopEntry.of("rockets", "minecraft:firework_rocket", 64, coin, 128, working));
        catalog.put(ShopEntry.of("book_unbreaking", "minecraft:enchanted_book", 1, coin, 192, working,
                "{\"minecraft:stored_enchantments\":{\"minecraft:unbreaking\":3}}"));
        catalog.put(ShopEntry.of("book_efficiency", "minecraft:enchanted_book", 1, coin, 256, working,
                "{\"minecraft:stored_enchantments\":{\"minecraft:efficiency\":4}}"));
        catalog.put(ShopEntry.of("champion_helm", "minecraft:iron_helmet", 1, valor, 150, honours,
                "{\"minecraft:item_name\":\"Champion's Helm\","
                        + "\"minecraft:trim\":{\"material\":\"minecraft:gold\",\"pattern\":\"minecraft:silence\"}}"));
        catalog.put(ShopEntry.of("champion_head", "minecraft:player_head", 1, valor, 80, honours,
                "{\"minecraft:item_name\":\"Champion's Trophy\"}"));
        catalog.put(ShopEntry.of("valor_banner", "minecraft:white_banner", 1, valor, 40, honours,
                "{\"minecraft:item_name\":\"Banner of Valor\"}"));

        save(catalog);
        CharterCurrencyMod.LOG.info("Wrote a starter shop.json -- edit it to set your own prices");
    }
}
