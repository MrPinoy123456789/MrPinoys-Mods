package chartercurrency;

import chartercurrency.core.EarnService;
import chartercurrency.core.Treasury;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code earn.json} and {@code renown_catalog.json}. Both are written with defaults on
 * first run and reloadable. A file that cannot be read keeps what was loaded before
 * (or the defaults at boot) and logs once.
 */
final class EarnConfigFile {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private EarnConfigFile() {}

    // ---- earn.json ----------------------------------------------------------

    static EarnService.Config defaultsEarn() {
        Map<String, EarnService.Source> sources = new LinkedHashMap<>();
        sources.put("dungeon", new EarnService.Source("dungeon", 10, 2, 150, 0, 1.0));
        sources.put("event", new EarnService.Source("event", 20, 0, 0, 0, 1.0));
        sources.put("buildoff_1st", new EarnService.Source("buildoff_1st", 100, 0, 0, 0, 1.0));
        sources.put("buildoff_2nd", new EarnService.Source("buildoff_2nd", 60, 0, 0, 0, 1.0));
        sources.put("buildoff_3rd", new EarnService.Source("buildoff_3rd", 30, 0, 0, 0, 1.0));
        // Fantasy's Fortune pays its Valor through this source (base 4 + 2 per level, soft cap 60).
        // The half Renown multiplier keeps alt accounts from farming a kingdom's Renown (EC-10).
        sources.put("fortune", new EarnService.Source("fortune", 4, 2, 60, 0, 0.5));
        return new EarnService.Config(sources, 120, 30, 7);
    }

    static EarnService.Config loadEarn(Path directory, EarnService.Config fallback) {
        Path file = directory.resolve("earn.json");
        if (!Files.exists(file)) {
            writeEarn(file, defaultsEarn());
            return defaultsEarn();
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
            Map<String, EarnService.Source> sources = new LinkedHashMap<>();
            JsonObject fileSources = root.getAsJsonObject("sources");
            for (Map.Entry<String, JsonElement> e : fileSources.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                String key = e.getKey().toLowerCase(Locale.ROOT);
                sources.put(key, new EarnService.Source(key,
                        num(o, "base", 0), num(o, "perLevel", 0), Math.max(0, num(o, "softCap", 0)),
                        Math.max(0, num(o, "hardCap", 0)),
                        o.has("renownMultiplier") ? o.get("renownMultiplier").getAsDouble() : 1.0));
            }
            long perPlayer = 120;
            long window = 30;
            int probation = 7;
            if (root.has("renown")) {
                JsonObject r = root.getAsJsonObject("renown");
                perPlayer = Math.max(0, num(r, "perPlayerDailyCap", 120));
                window = Math.max(0, num(r, "runWindowSeconds", 30));
                probation = (int) Math.max(0, num(r, "probationDays", 7));
            }
            EarnService.Config loaded = new EarnService.Config(sources, perPlayer, window, probation);
            // A file written before a source existed (fortune) keeps its own values; only the
            // missing sources are added, in memory and in the file, so the operator can see them.
            EarnService.Config defaults = defaultsEarn();
            List<String> missing = loaded.missingFrom(defaults);
            if (missing.isEmpty()) return loaded;
            for (String key : missing) {
                fileSources.add(key, sourceJson(defaults.sources().get(key)));
            }
            write(file, root);
            CharterCurrencyMod.LOG.info("earn.json was missing the default source(s) {}; added them with the shipped values",
                    missing);
            return loaded.withMissingFrom(defaults);
        } catch (Exception e) {
            CharterCurrencyMod.LOG.warn("earn.json could not be read; keeping the previous settings", e);
            return fallback;
        }
    }

    private static JsonObject sourceJson(EarnService.Source s) {
        JsonObject o = new JsonObject();
        o.addProperty("base", s.base());
        o.addProperty("perLevel", s.perLevel());
        o.addProperty("softCap", s.softCap());
        o.addProperty("hardCap", s.hardCap());
        o.addProperty("renownMultiplier", s.renownMultiplier());
        return o;
    }

    private static void writeEarn(Path file, EarnService.Config cfg) {
        JsonObject sources = new JsonObject();
        for (EarnService.Source s : cfg.sources().values()) {
            sources.add(s.key(), sourceJson(s));
        }
        JsonObject renown = new JsonObject();
        renown.addProperty("perPlayerDailyCap", cfg.perPlayerDailyCap());
        renown.addProperty("runWindowSeconds", cfg.runWindowSeconds());
        renown.addProperty("probationDays", cfg.probationDays());
        JsonObject root = new JsonObject();
        root.add("sources", sources);
        root.add("renown", renown);
        write(file, root);
    }

    // ---- renown_catalog.json ------------------------------------------------

    static List<Treasury.CatalogEntry> defaultsCatalog() {
        List<Treasury.CatalogEntry> out = new ArrayList<>();
        out.add(tier("tier_hamlet", "Hamlet Charter", "Hamlet", 300, null));
        out.add(tier("tier_village", "Village Charter", "Village", 900, "tier_hamlet"));
        out.add(tier("tier_town", "Town Charter", "Town", 2000, "tier_village"));
        out.add(tier("tier_city", "City Charter", "City", 4000, "tier_town"));
        out.add(tier("tier_realm", "Realm Charter", "Realm", 8000, "tier_city"));
        return out;
    }

    private static Treasury.CatalogEntry tier(String key, String name, String word, long cost, String requires) {
        String article = "a ";
        return new Treasury.CatalogEntry(key, name, "Raise your kingdom to " + article + word + ".", cost,
                false, requires, List.of(
                "tellraw @a {\"text\":\"%kingdom_name% is now " + article + word + ".\",\"color\":\"gold\"}"));
    }

    static List<Treasury.CatalogEntry> loadCatalog(Path directory, List<Treasury.CatalogEntry> fallback) {
        Path file = directory.resolve("renown_catalog.json");
        if (!Files.exists(file)) {
            writeCatalog(file, defaultsCatalog());
            return defaultsCatalog();
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
            List<Treasury.CatalogEntry> out = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                List<String> commands = new ArrayList<>();
                if (o.has("commands")) for (JsonElement c : o.getAsJsonArray("commands")) commands.add(c.getAsString());
                String requires = o.has("requires") && !o.get("requires").isJsonNull()
                        ? o.get("requires").getAsString() : null;
                out.add(new Treasury.CatalogEntry(o.get("key").getAsString(),
                        o.has("name") ? o.get("name").getAsString() : o.get("key").getAsString(),
                        o.has("description") ? o.get("description").getAsString() : "",
                        Math.max(0, o.get("cost").getAsLong()),
                        o.has("repeatable") && o.get("repeatable").getAsBoolean(), requires, commands));
            }
            return out;
        } catch (Exception e) {
            CharterCurrencyMod.LOG.warn("renown_catalog.json could not be read; keeping the previous catalogue", e);
            return fallback;
        }
    }

    private static void writeCatalog(Path file, List<Treasury.CatalogEntry> entries) {
        JsonArray array = new JsonArray();
        for (Treasury.CatalogEntry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("key", e.key());
            o.addProperty("name", e.name());
            o.addProperty("description", e.description());
            o.addProperty("cost", e.cost());
            o.addProperty("repeatable", e.repeatable());
            if (e.requires() == null) o.add("requires", com.google.gson.JsonNull.INSTANCE);
            else o.addProperty("requires", e.requires());
            JsonArray commands = new JsonArray();
            for (String c : e.commands()) commands.add(c);
            o.add("commands", commands);
            array.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("entries", array);
        write(file, root);
    }

    // ---- helpers ------------------------------------------------------------

    private static long num(JsonObject o, String key, long fallback) {
        return o.has(key) ? o.get(key).getAsLong() : fallback;
    }

    private static void write(Path file, JsonObject root) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, out);
            }
        } catch (Exception e) {
            CharterCurrencyMod.LOG.warn("Could not write {}", file.getFileName(), e);
        }
    }
}
