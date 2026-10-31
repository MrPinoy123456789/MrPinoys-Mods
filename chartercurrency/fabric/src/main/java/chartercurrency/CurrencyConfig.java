package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.CurrencyRegistry;
import chartercurrency.core.Denomination;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code config/chartercurrency/currencies.json}: the coin denominations and Valor.
 *
 * <p>The defaults ship as the file's first contents. The marker stamped on each coin is
 * always {@code {"chartercurrency":{"coin":"<id>"}}} and is not configurable, so a
 * config edit cannot turn plain nuggets into money.
 *
 * <p>A bad file keeps the defaults and logs once; nobody's balance depends on this file
 * (balances are keyed by currency id, and the ids below are the contract).
 */
final class CurrencyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CurrencyConfig() {}

    /** Loads the registry, writing the default file on first run. */
    static CurrencyRegistry load(Path directory) {
        Path file = directory.resolve("currencies.json");
        if (!Files.exists(file)) {
            write(file, CurrencyRegistry.defaults());
            return CurrencyRegistry.defaults();
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
            CurrencyRegistry registry = new CurrencyRegistry();
            registry.register(parseCoin(root.getAsJsonObject("coin")));
            registry.register(parseValor(root.getAsJsonObject("valor")));
            return registry;
        } catch (Exception e) {
            CharterCurrencyMod.LOG.warn("currencies.json could not be read; using the defaults", e);
            return CurrencyRegistry.defaults();
        }
    }

    private static Currency parseCoin(JsonObject o) {
        List<Denomination> denominations = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("denominations")) {
            JsonObject d = el.getAsJsonObject();
            denominations.add(new Denomination(
                    d.get("id").getAsString(),
                    d.get("singular").getAsString(),
                    d.get("plural").getAsString(),
                    d.get("item").getAsString(),
                    d.get("value").getAsInt(),
                    strings(d.getAsJsonArray("aliases"))));
        }
        if (denominations.isEmpty()) throw new IllegalArgumentException("coin needs denominations");
        return new Currency("coin", string(o, "displayName", "Coin"),
                string(o, "baseUnit", "Mark"), string(o, "baseUnit", "Mark") + "s", null,
                Set.of("coin", "coins"),
                bool(o, "withdrawable", true), bool(o, "payable", true), denominations);
    }

    private static Currency parseValor(JsonObject o) {
        String name = string(o, "displayName", "Valor");
        return new Currency("valor", name, name, name, null,
                strings(o.getAsJsonArray("aliases")),
                bool(o, "withdrawable", false), bool(o, "payable", false), List.of());
    }

    private static void write(Path file, CurrencyRegistry registry) {
        JsonObject root = new JsonObject();
        Currency coin = CurrencyRegistry.COIN;
        JsonObject c = new JsonObject();
        c.addProperty("displayName", coin.displayName());
        c.addProperty("baseUnit", coin.singular());
        c.addProperty("withdrawable", coin.withdrawable());
        c.addProperty("payable", coin.payable());
        JsonArray denominations = new JsonArray();
        for (Denomination d : coin.denominations()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id());
            o.addProperty("singular", d.singular());
            o.addProperty("plural", d.plural());
            o.addProperty("item", d.itemId());
            o.addProperty("value", d.value());
            o.add("aliases", aliasesFor(d));
            denominations.add(o);
        }
        c.add("denominations", denominations);
        root.add("coin", c);

        Currency valor = CurrencyRegistry.VALOR;
        JsonObject v = new JsonObject();
        v.addProperty("displayName", valor.displayName());
        v.addProperty("withdrawable", valor.withdrawable());
        v.addProperty("payable", valor.payable());
        JsonArray valorAliases = new JsonArray();
        valorAliases.add("valor");
        v.add("aliases", valorAliases);
        root.add("valor", v);

        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, out);
            }
        } catch (Exception e) {
            CharterCurrencyMod.LOG.warn("Could not write currencies.json", e);
        }
    }

    private static JsonArray aliasesFor(Denomination d) {
        JsonArray out = new JsonArray();
        switch (d.id()) {
            case "copper" -> { out.add("mark"); out.add("marks"); out.add("copper"); }
            case "iron" -> { out.add("crown"); out.add("crowns"); out.add("iron"); }
            case "gold" -> { out.add("sovereign"); out.add("sovereigns"); out.add("gold"); out.add("sov"); }
            default -> { for (String a : d.aliases()) out.add(a); }
        }
        return out;
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null) for (JsonElement e : array) out.add(e.getAsString());
        return out;
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }
}
