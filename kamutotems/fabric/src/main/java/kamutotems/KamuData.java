package kamutotems;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import kamutotems.core.Category;
import kamutotems.core.HostType;
import kamutotems.core.Kamu;
import kamutotems.core.KamuCatalog;
import kamutotems.core.Polarity;
import kamutotems.core.Rarity;
import kamutotems.core.ReactionEngine;
import kamutotems.core.ReactionRule;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the kamu catalog and the reaction table from disk, so an operator can
 * add a kamu or a reaction without a code change (SPEC.md sections 5.5, 5.6).
 *
 * <p><b>The two files fail in opposite directions, deliberately</b>
 * (SPEC.md section 17):
 *
 * <ul>
 *   <li>{@code reactions.json} unparseable -&gt; <b>defaults in memory, file
 *       untouched.</b> Reactions still work; the operator's broken edit survives
 *       for them to fix. Nobody's data is at stake.</li>
 *   <li>{@code components.json} unparseable -&gt; <b>the server refuses to
 *       start.</b> Booting with an empty or partial catalog would make every
 *       slotted kamu resolve to "unknown" and the next totem write would erase
 *       every player's Kamuy. Refusing to boot is the recoverable failure.</li>
 * </ul>
 *
 * That asymmetry is the whole point of this class -- one config failing open and
 * the other failing closed is a decision, not an oversight.
 */
public final class KamuData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static KamuCatalog catalog = KamuCatalog.defaults();
    private static ReactionEngine reactions = ReactionEngine.defaults();

    private KamuData() {}

    public static KamuCatalog catalog() {
        return catalog;
    }

    public static ReactionEngine reactions() {
        return reactions;
    }

    public static void load(Path configDir) {
        catalog = loadCatalog(configDir.resolve("components.json"));
        reactions = loadReactions(configDir.resolve("reactions.json"));
    }

    // ---- catalog: fails CLOSED --------------------------------------------

    private static KamuCatalog loadCatalog(Path file) {
        try {
            if (!Files.exists(file)) {
                writeCatalogDefaults(file);
                KamuTotemsMod.LOG.info("Created default components.json ({} kamu)",
                        KamuCatalog.defaults().all().size());
                return KamuCatalog.defaults();
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                KamuCatalog loaded = readCatalog(r);
                KamuTotemsMod.LOG.info("Loaded {} kamu from components.json", loaded.all().size());
                return loaded;
            }
        } catch (Exception e) {
            // Chosen failure: refuse to boot. An empty catalog looks fine until
            // the next totem write silently erases every player's build.
            throw new IllegalStateException(
                    "components.json could not be read. The server is refusing to start rather "
                    + "than boot with an incomplete kamu catalog, which would erase player "
                    + "totems on the next write. Fix the file, or delete it to regenerate "
                    + "defaults.", e);
        }
    }

    static KamuCatalog readCatalog(Reader reader) {
        JsonObject root = GSON.fromJson(reader, JsonObject.class);
        if (root == null || !root.has("kamu") || !root.get("kamu").isJsonArray()) {
            throw new IllegalStateException("components.json has no kamu array");
        }
        List<Kamu> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("kamu")) {
            out.add(readKamu(el.getAsJsonObject()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("components.json defines no kamu");
        }
        return new KamuCatalog(out);
    }

    private static Kamu readKamu(JsonObject o) {
        Map<String, Double> params = new LinkedHashMap<>();
        if (o.has("parameters")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("parameters").entrySet()) {
                params.put(e.getKey(), e.getValue().getAsDouble());
            }
        }
        Set<String> tags = new LinkedHashSet<>();
        if (o.has("tags")) {
            for (JsonElement e : o.getAsJsonArray("tags")) {
                tags.add(e.getAsString());
            }
        }
        Set<HostType> hosts = new LinkedHashSet<>();
        if (o.has("allowedHosts")) {
            for (JsonElement e : o.getAsJsonArray("allowedHosts")) {
                hosts.add(HostType.valueOf(e.getAsString()));
            }
        }
        Polarity polarity = o.has("polarity")
                ? Polarity.valueOf(o.get("polarity").getAsString())
                : Polarity.NONE;
        return new Kamu(
                o.get("id").getAsString(),
                o.get("displayName").getAsString(),
                Category.valueOf(o.get("category").getAsString()),
                Rarity.valueOf(o.get("rarity").getAsString()),
                o.get("complexity").getAsInt(),
                o.get("effectId").getAsString(),
                params, tags, hosts, polarity);
    }

    private static void writeCatalogDefaults(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeCatalog(w, KamuCatalog.defaults());
        }
    }

    static void writeCatalog(Writer writer, KamuCatalog catalog) {
        JsonArray arr = new JsonArray();
        for (Kamu k : catalog.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", k.id());
            o.addProperty("displayName", k.displayName());
            o.addProperty("category", k.category().name());
            o.addProperty("rarity", k.rarity().name());
            o.addProperty("complexity", k.complexity());
            o.addProperty("effectId", k.effectId());
            o.addProperty("polarity", k.polarity().name());
            JsonObject params = new JsonObject();
            k.parameters().forEach(params::addProperty);
            o.add("parameters", params);
            JsonArray tags = new JsonArray();
            k.tags().forEach(tags::add);
            o.add("tags", tags);
            JsonArray hosts = new JsonArray();
            k.allowedHosts().forEach(h -> hosts.add(h.name()));
            o.add("allowedHosts", hosts);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("kamu", arr);
        GSON.toJson(root, writer);
    }

    // ---- reactions: fails OPEN --------------------------------------------

    private static ReactionEngine loadReactions(Path file) {
        try {
            if (!Files.exists(file)) {
                writeReactionDefaults(file);
                KamuTotemsMod.LOG.info("Created default reactions.json");
                return ReactionEngine.defaults();
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject root = GSON.fromJson(r, JsonObject.class);
                int depthCap = root.has("depthCap") ? root.get("depthCap").getAsInt() : 4;
                List<ReactionRule> rules = new ArrayList<>();
                for (JsonElement el : root.getAsJsonArray("rules")) {
                    JsonObject o = el.getAsJsonObject();
                    Set<String> conditions = new LinkedHashSet<>();
                    if (o.has("conditions")) {
                        for (JsonElement c : o.getAsJsonArray("conditions")) {
                            conditions.add(c.getAsString());
                        }
                    }
                    JsonElement outcome = o.get("outcomeEffectId");
                    rules.add(new ReactionRule(
                            o.get("id").getAsString(),
                            o.get("a").getAsString(),
                            o.get("b").getAsString(),
                            conditions,
                            outcome == null || outcome.isJsonNull() ? null : outcome.getAsString(),
                            o.get("priority").getAsInt(),
                            o.get("terminal").getAsBoolean()));
                }
                KamuTotemsMod.LOG.info("Loaded {} reactions from reactions.json", rules.size());
                return new ReactionEngine(rules, Math.max(1, depthCap));
            }
        } catch (Exception e) {
            // Chosen failure: defaults in memory, file untouched. Reactions
            // still work and the operator's edit is still there to fix.
            KamuTotemsMod.LOG.error("reactions.json could not be read; using the built-in "
                    + "table and leaving the file alone", e);
            return ReactionEngine.defaults();
        }
    }

    private static void writeReactionDefaults(Path file) throws Exception {
        // The default table is defined once, in core. Rather than duplicate it
        // here, serialise what ReactionEngine.defaults() actually holds.
        JsonArray arr = new JsonArray();
        for (ReactionRule rule : ReactionEngine.defaults().rules()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", rule.id());
            o.addProperty("a", rule.a());
            o.addProperty("b", rule.b());
            JsonArray conditions = new JsonArray();
            rule.conditions().forEach(conditions::add);
            o.add("conditions", conditions);
            if (rule.outcomeEffectId() == null) {
                o.add("outcomeEffectId", com.google.gson.JsonNull.INSTANCE);
            } else {
                o.addProperty("outcomeEffectId", rule.outcomeEffectId());
            }
            o.addProperty("priority", rule.priority());
            o.addProperty("terminal", rule.terminal());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("depthCap", 4);
        root.add("rules", arr);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }
}
