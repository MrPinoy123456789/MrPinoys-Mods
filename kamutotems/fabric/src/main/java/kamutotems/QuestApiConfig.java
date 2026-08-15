package kamutotems;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import kamutotems.core.EventMatcher;
import kamutotems.core.QuestCatalog;
import kamutotems.core.QuestDefinition;
import kamutotems.core.QuestReward;
import kamutotems.core.QuestSegment;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the assigned-quest definition catalog from {@code config/kamutotems/quests.json}.
 *
 * <p>Fails OPEN: an unparseable file leaves the defaults in memory and does
 * <em>not</em> overwrite the operator's broken edit. Illegal rewards (anything
 * that would hand out a kamu) are rejected at load with a loud log entry.
 */
public final class QuestApiConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static QuestCatalog catalog = QuestCatalog.defaults();

    private QuestApiConfig() {}

    public static QuestCatalog catalog() {
        return catalog;
    }

    public static void load(Path configDir) {
        Path file = configDir.resolve("quests.json");
        try {
            if (!Files.exists(file)) {
                writeDefaults(file);
                KamuTotemsMod.LOG.info("Created default quests.json");
                catalog = QuestCatalog.defaults();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject root = GSON.fromJson(r, JsonObject.class);
                catalog = readCatalog(root);
                KamuTotemsMod.LOG.info("Loaded {} quest definitions from quests.json",
                        catalog.all().size());
            }
        } catch (Exception e) {
            KamuTotemsMod.LOG.error("quests.json could not be read; using default quest catalog "
                    + "and leaving the file alone", e);
            catalog = QuestCatalog.defaults();
        }
    }

    private static QuestCatalog readCatalog(JsonObject root) {
        List<QuestDefinition> defs = new ArrayList<>();
        if (root != null && root.has("quests") && root.get("quests").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("quests")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                try {
                    QuestDefinition def = readDefinition(el.getAsJsonObject());
                    if (def == null) {
                        continue;
                    }
                    if (hasIllegalReward(def)) {
                        KamuTotemsMod.LOG.error("Quest '{}' defines a reward that would grant a "
                                + "kamu; the whole definition was rejected.", def.id());
                        continue;
                    }
                    defs.add(def);
                } catch (Exception e) {
                    KamuTotemsMod.LOG.error("Failed to read a quest definition; skipped", e);
                }
            }
        }
        if (defs.isEmpty()) {
            KamuTotemsMod.LOG.warn("quests.json contained no valid quest definitions; "
                    + "using defaults");
            return QuestCatalog.defaults();
        }
        return new QuestCatalog(defs);
    }

    private static QuestDefinition readDefinition(JsonObject o) {
        String id = o.get("id").getAsString();
        String title = o.get("title").getAsString();
        String sourceLabel = o.has("sourceLabel") ? o.get("sourceLabel").getAsString() : "";

        List<QuestSegment> segments = new ArrayList<>();
        if (o.has("segments") && o.get("segments").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("segments")) {
                segments.add(readSegment(el.getAsJsonObject()));
            }
        }

        List<QuestReward> rewards = new ArrayList<>();
        if (o.has("rewards") && o.get("rewards").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("rewards")) {
                rewards.add(readReward(el.getAsJsonObject()));
            }
        }

        int expiryDays = o.has("expiryDays") ? o.get("expiryDays").getAsInt() : 0;
        boolean repeatable = o.has("repeatable") && o.get("repeatable").getAsBoolean();

        return new QuestDefinition(id, title, sourceLabel, List.copyOf(segments),
                List.copyOf(rewards), expiryDays, repeatable);
    }

    private static QuestSegment readSegment(JsonObject o) {
        String kind = o.get("kind").getAsString();
        String displayText = o.get("displayText").getAsString();

        JsonObject matcherObj = o.getAsJsonObject("matcher");
        String eventId = matcherObj.has("eventId") && !matcherObj.get("eventId").isJsonNull()
                ? matcherObj.get("eventId").getAsString()
                : null;
        int requiredCount = matcherObj.get("requiredCount").getAsInt();

        Map<String, String> predicate = new LinkedHashMap<>();
        if (matcherObj.has("predicate") && matcherObj.get("predicate").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : matcherObj.getAsJsonObject("predicate").entrySet()) {
                predicate.put(e.getKey(), e.getValue().getAsString());
            }
        }

        return new QuestSegment(kind, new EventMatcher(eventId, predicate, requiredCount), displayText);
    }

    private static QuestReward readReward(JsonObject o) {
        String kind = o.get("kind").getAsString();
        String itemId = o.has("itemId") && !o.get("itemId").isJsonNull()
                ? o.get("itemId").getAsString()
                : null;
        int amount = o.has("amount") ? o.get("amount").getAsInt() : 1;
        String note = o.has("note") && !o.get("note").isJsonNull()
                ? o.get("note").getAsString()
                : null;
        return new QuestReward(kind, itemId, amount, note);
    }

    private static boolean hasIllegalReward(QuestDefinition def) {
        for (QuestReward reward : def.rewards()) {
            if (!reward.isLegal()) {
                return true;
            }
            // Also guard against custom kamu added to components.json that the
            // core defaults guard cannot know about.
            if ("item".equals(reward.kind()) && reward.itemId() != null
                    && KamuData.catalog().get(reward.itemId()) != null) {
                return true;
            }
        }
        return false;
    }

    private static void writeDefaults(Path file) throws Exception {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (QuestDefinition def : QuestCatalog.defaults().all()) {
            arr.add(writeDefinition(def));
        }
        root.add("quests", arr);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }

    private static JsonObject writeDefinition(QuestDefinition def) {
        JsonObject o = new JsonObject();
        o.addProperty("id", def.id());
        o.addProperty("title", def.title());
        o.addProperty("sourceLabel", def.sourceLabel());

        JsonArray segments = new JsonArray();
        for (QuestSegment seg : def.segments()) {
            JsonObject segObj = new JsonObject();
            segObj.addProperty("kind", seg.kind());
            segObj.addProperty("displayText", seg.displayText());

            JsonObject matcher = new JsonObject();
            if (seg.matcher().eventId() != null) {
                matcher.addProperty("eventId", seg.matcher().eventId());
            }
            matcher.addProperty("requiredCount", seg.matcher().requiredCount());
            JsonObject predicate = new JsonObject();
            for (Map.Entry<String, String> e : seg.matcher().predicate().entrySet()) {
                predicate.addProperty(e.getKey(), e.getValue());
            }
            matcher.add("predicate", predicate);
            segObj.add("matcher", matcher);

            segments.add(segObj);
        }
        o.add("segments", segments);

        JsonArray rewards = new JsonArray();
        for (QuestReward reward : def.rewards()) {
            JsonObject r = new JsonObject();
            r.addProperty("kind", reward.kind());
            if (reward.itemId() == null) {
                r.add("itemId", JsonNull.INSTANCE);
            } else {
                r.addProperty("itemId", reward.itemId());
            }
            r.addProperty("amount", reward.amount());
            if (reward.note() == null) {
                r.add("note", JsonNull.INSTANCE);
            } else {
                r.addProperty("note", reward.note());
            }
            rewards.add(r);
        }
        o.add("rewards", rewards);

        o.addProperty("expiryDays", def.expiryDays());
        o.addProperty("repeatable", def.repeatable());
        return o;
    }
}
