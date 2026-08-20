package hearsay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import hearsay.core.DefaultLines;
import hearsay.core.LinePools;
import hearsay.core.ReadOrCreate;
import hearsay.core.Scene;
import hearsay.core.Script;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator-facing config for Hearsay.
 *
 * <p>Uses {@link ReadOrCreate} from core so the file lifecycle rules are tested,
 * while the actual JSON parsing stays in the fabric module where Gson lives.
 * Default lines are rendered from {@link DefaultLines} so the shipped content
 * lives in exactly one place.
 */
public final class HearsayConfig {

    /** Default delivery channel for each dialogue moment or pool family. */
    private static final Map<String, String> DEFAULT_CHANNELS = Map.of(
            "ambient", "actionbar",
            "greeting", "actionbar",
            "traded", "actionbar",
            "morning", "actionbar",
            "night", "actionbar",
            "weather", "actionbar",
            "reaction", "chat",
            "say", "bubble",
            "narrate", "chat");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path dir;
    private Settings settings = defaultSettings();
    private LinePools pools = new LinePools(DefaultLines.pools()).withDefaults(null);
    private List<Scene> scenes = DefaultLines.scenes();

    public HearsayConfig(Path configDir) {
        this.dir = configDir.resolve(HearsayMod.MOD_ID);
    }

    /** Reloads settings, dialogue pools, and scenes without overwriting malformed files. */
    public void reload() {
        Path settingsFile = dir.resolve("settings.json");
        ReadOrCreate.Result<Settings> s = ReadOrCreate.load(
                settingsFile, defaultSettings(), HearsayConfig::parseSettings,
                HearsayConfig::renderSettings, HearsayMod.LOG::info);
        this.settings = s.value();

        Path linesFile = dir.resolve("lines.json");
        ReadOrCreate.Result<Lines> l = ReadOrCreate.load(
                linesFile, defaultLines(), HearsayConfig::parseLines,
                HearsayConfig::renderLines, HearsayMod.LOG::info);
        this.pools = new LinePools(l.value().pools()).withDefaults(new LinePools(DefaultLines.pools()));
        this.scenes = l.value().scenes().isEmpty() ? DefaultLines.scenes() : l.value().scenes();
    }

    public Settings settings() { return settings; }
    public LinePools pools() { return pools; }
    public List<Scene> scenes() { return scenes; }

    // ------------------------------------------------------------------ defaults

    private static Settings defaultSettings() {
        return new Settings(
                90,
                16,
                240,
                600,
                2,
                0.04,
                0.02,
                8,
                LinePools.DEFAULT_PROFESSION_CHANCE,
                DEFAULT_CHANNELS);
    }

    private static Lines defaultLines() {
        return new Lines(DefaultLines.pools(), DefaultLines.scenes());
    }

    // ------------------------------------------------------------------ settings

    private record Lines(Map<String, List<String>> pools, List<Scene> scenes) {}

    public record Settings(
            int quietSeconds,
            int hearingRange,
            int speakerCooldownSeconds,
            int greetingCooldownSeconds,
            int maxSimultaneousScenes,
            double sceneChance,
            double ambientChance,
            int suppressAfterDamageSeconds,
            /** Share of a specialist's lines drawn from their own pool vs the shared one. */
            double professionLineChance,
            Map<String, String> channel) {}

    private static Settings parseSettings(String text) {
        JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
        return new Settings(
                getInt(obj, "quietSeconds", 90),
                getInt(obj, "hearingRange", 16),
                getInt(obj, "speakerCooldownSeconds", 240),
                getInt(obj, "greetingCooldownSeconds", 600),
                getInt(obj, "maxSimultaneousScenes", 2),
                getDouble(obj, "sceneChance", 0.04),
                getDouble(obj, "ambientChance", 0.02),
                getInt(obj, "suppressAfterDamageSeconds", 8),
                getDouble(obj, "professionLineChance", LinePools.DEFAULT_PROFESSION_CHANCE),
                parseChannel(obj.getAsJsonObject("channel")));
    }

    private static String renderSettings(Settings s) {
        JsonObject obj = new JsonObject();
        obj.addProperty("quietSeconds", s.quietSeconds());
        obj.addProperty("hearingRange", s.hearingRange());
        obj.addProperty("speakerCooldownSeconds", s.speakerCooldownSeconds());
        obj.addProperty("greetingCooldownSeconds", s.greetingCooldownSeconds());
        obj.addProperty("maxSimultaneousScenes", s.maxSimultaneousScenes());
        obj.addProperty("sceneChance", s.sceneChance());
        obj.addProperty("ambientChance", s.ambientChance());
        obj.addProperty("suppressAfterDamageSeconds", s.suppressAfterDamageSeconds());
        obj.addProperty("professionLineChance", s.professionLineChance());
        JsonObject ch = new JsonObject();
        for (Map.Entry<String, String> e : s.channel().entrySet()) {
            ch.addProperty(e.getKey(), e.getValue());
        }
        obj.add("channel", ch);
        return GSON.toJson(obj);
    }

    private static Map<String, String> parseChannel(JsonObject obj) {
        if (obj == null) return DEFAULT_CHANNELS;
        Map<String, String> out = new LinkedHashMap<>(DEFAULT_CHANNELS);
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------------ lines

    private static Lines parseLines(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        Map<String, List<String>> pools = new LinkedHashMap<>();
        List<Scene> scenes = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if ("scenes".equals(entry.getKey()) && entry.getValue().isJsonArray()) {
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        Scene sc = parseScene(el.getAsJsonObject());
                        if (sc != null) scenes.add(sc);
                    }
                }
            } else if (entry.getValue().isJsonArray()) {
                List<String> lines = new ArrayList<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    if (el.isJsonPrimitive()) lines.add(el.getAsString());
                }
                if (!lines.isEmpty()) pools.put(entry.getKey(), lines);
            }
        }
        return new Lines(pools, scenes);
    }

    private static String renderLines(Lines lines) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, List<String>> pool : lines.pools().entrySet()) {
            JsonArray arr = new JsonArray();
            for (String line : pool.getValue()) arr.add(line);
            root.add(pool.getKey(), arr);
        }
        JsonArray scenes = new JsonArray();
        for (Scene scene : lines.scenes()) {
            scenes.add(renderScene(scene));
        }
        root.add("scenes", scenes);
        return GSON.toJson(root);
    }

    private static Scene parseScene(JsonObject obj) {
        String s0;
        String s1;
        JsonElement sp = obj.get("speakers");
        if (sp != null && sp.isJsonArray()) {
            JsonArray arr = sp.getAsJsonArray();
            s0 = arr.size() > 0 ? arr.get(0).getAsString() : "*";
            s1 = arr.size() > 1 ? arr.get(1).getAsString() : "*";
        } else {
            s0 = getString(obj, "speaker0");
            s1 = getString(obj, "speaker1");
        }
        if (s0.isBlank()) s0 = "*";
        if (s1.isBlank()) s1 = "*";
        String channel = getString(obj, "channel");
        if (channel.isBlank()) channel = "chat";
        Script script = parseScript(obj.getAsJsonArray("script"));
        if (script == null) script = new Script(List.of());
        return new Scene(s0, s1, channel, script);
    }

    private static JsonObject renderScene(Scene scene) {
        JsonObject obj = new JsonObject();
        JsonArray speakers = new JsonArray();
        speakers.add(scene.speaker0());
        speakers.add(scene.speaker1());
        obj.add("speakers", speakers);
        obj.addProperty("channel", scene.channel());
        obj.add("script", renderScript(scene.script()));
        return obj;
    }

    private static Script parseScript(JsonArray arr) {
        if (arr == null) return null;
        List<Script.Step> steps = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("say")) {
                steps.add(Script.Step.say(getString(o, "say"), getInt(o, "speaker", 0)));
            } else if (o.has("narrate")) {
                steps.add(Script.Step.narrate(getString(o, "narrate")));
            } else if (o.has("wait")) {
                steps.add(Script.Step.waitTicks(getInt(o, "wait", 0)));
            } else if (o.has("action")) {
                steps.add(Script.Step.action(getString(o, "action")));
            }
        }
        return new Script(steps);
    }

    private static JsonArray renderScript(Script script) {
        JsonArray arr = new JsonArray();
        for (Script.Step step : script.steps()) {
            JsonObject o = new JsonObject();
            switch (step.kind()) {
                case SAY -> {
                    o.addProperty("say", step.text());
                    o.addProperty("speaker", step.speaker());
                }
                case NARRATE -> o.addProperty("narrate", step.text());
                case WAIT -> o.addProperty("wait", step.waitTicks());
                case ACTION -> o.addProperty("action", step.action());
            }
            arr.add(o);
        }
        return arr;
    }

    // ------------------------------------------------------------------ helpers

    private static String getString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return "";
        return e.getAsString();
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsInt(); } catch (NumberFormatException ex) { return fallback; }
    }

    private static double getDouble(JsonObject obj, String key, double fallback) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsDouble(); } catch (NumberFormatException ex) { return fallback; }
    }
}
