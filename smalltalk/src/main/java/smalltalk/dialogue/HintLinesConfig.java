package smalltalk.dialogue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import smalltalk.SmallTalkMod;
import smalltalk.identity.ItemCategory;
import smalltalk.identity.Personality;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Small Talk button's data (SPEC.md's community-content spirit, same as
 * {@link DialogueLinesConfig}): personality -&gt; a handful of framing
 * sentences with a {@code {hint}} placeholder, and category -&gt; a handful
 * of oblique phrasings of what a resident likes. {@link HintSelector} picks
 * one of each and splices them together, so the same liked category reads
 * differently from a GRUFF resident than a DREAMY one without needing
 * forty-eight hand-written lines. Lives at {@code config/smalltalk/hints.json}.
 */
public final class HintLinesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Map<Personality, List<String>> frames = defaultFrames();
    private static Map<ItemCategory, List<String>> phrases = defaultPhrases();

    private HintLinesConfig() {}

    public static void load(Path configDir) {
        Path file = configDir.resolve(SmallTalkMod.MOD_ID).resolve("hints.json");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(toJson(defaultFrames(), defaultPhrases()), w);
                }
                SmallTalkMod.LOG.info("Created default smalltalk/hints.json");
                frames = defaultFrames();
                phrases = defaultPhrases();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(r, JsonObject.class);
                if (parsed == null) {
                    throw new IllegalStateException("smalltalk/hints.json is empty");
                }
                frames = framesFromJson(parsed.getAsJsonObject("frames"));
                phrases = phrasesFromJson(parsed.getAsJsonObject("phrases"));
            }
        } catch (Exception e) {
            SmallTalkMod.LOG.error("smalltalk/hints.json could not be read; using defaults and "
                    + "leaving the file alone", e);
            frames = defaultFrames();
            phrases = defaultPhrases();
        }
    }

    public static List<String> framesFor(Personality personality) {
        return frames.getOrDefault(personality, List.of("{hint}"));
    }

    public static List<String> phrasesFor(ItemCategory category) {
        return phrases.getOrDefault(category, List.of());
    }

    private static Map<Personality, List<String>> framesFromJson(JsonObject root) {
        if (root == null) {
            return defaultFrames();
        }
        Map<Personality, List<String>> out = new EnumMap<>(Personality.class);
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            Personality personality;
            try {
                personality = Personality.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!entry.getValue().isJsonArray()) {
                continue;
            }
            out.put(personality, stringList(entry.getValue().getAsJsonArray()));
        }
        return out.isEmpty() ? defaultFrames() : out;
    }

    private static Map<ItemCategory, List<String>> phrasesFromJson(JsonObject root) {
        if (root == null) {
            return defaultPhrases();
        }
        Map<ItemCategory, List<String>> out = new EnumMap<>(ItemCategory.class);
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            ItemCategory category;
            try {
                category = ItemCategory.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!entry.getValue().isJsonArray()) {
                continue;
            }
            out.put(category, stringList(entry.getValue().getAsJsonArray()));
        }
        return out.isEmpty() ? defaultPhrases() : out;
    }

    private static List<String> stringList(JsonArray array) {
        List<String> pool = new ArrayList<>();
        array.forEach(el -> pool.add(el.getAsString()));
        return pool;
    }

    private static JsonObject toJson(Map<Personality, List<String>> frames, Map<ItemCategory, List<String>> phrases) {
        JsonObject root = new JsonObject();
        JsonObject frameObj = new JsonObject();
        frames.forEach((personality, pool) -> frameObj.add(personality.name(), toArray(pool)));
        root.add("frames", frameObj);
        JsonObject phraseObj = new JsonObject();
        phrases.forEach((category, pool) -> phraseObj.add(category.name(), toArray(pool)));
        root.add("phrases", phraseObj);
        return root;
    }

    private static JsonArray toArray(List<String> pool) {
        JsonArray arr = new JsonArray();
        pool.forEach(arr::add);
        return arr;
    }

    private static Map<Personality, List<String>> defaultFrames() {
        Map<Personality, List<String>> out = new EnumMap<>(Personality.class);
        out.put(Personality.CHIPPER, List.of(
                "Oh, since you're asking -- {hint}!",
                "You want to know a little secret about me? {hint}!",
                "Funny you should ask -- {hint}, always have."));
        out.put(Personality.GRUFF, List.of(
                "You want small talk? Fine. {hint}. That's it.",
                "Don't make a thing of it, but {hint}.",
                "{hint}. Now you know. Don't spread it around."));
        out.put(Personality.DREAMY, List.of(
                "Mm... you know, {hint}. Funny, the things that stay with you.",
                "I was just thinking -- {hint}. Strange, the mind wanders there.",
                "{hint}. Or maybe I only think that some days. Hard to say."));
        out.put(Personality.BRISK, List.of(
                "Quickly, then: {hint}. There, we've talked.",
                "Since you asked -- {hint}. Don't dawdle on my account.",
                "{hint}. That's all I've got time to say on it."));
        out.put(Personality.BASHFUL, List.of(
                "Oh -- um, if you really want to know... {hint}. Sorry, is that silly?",
                "I don't usually say, but -- {hint}. Please don't laugh.",
                "Well, um -- {hint}. I don't know why I told you that."));
        out.put(Personality.SMUG, List.of(
                "Since you're clearly dying to know: {hint}.",
                "I'll let you in on it, just this once -- {hint}.",
                "{hint}. Obviously. I have excellent taste."));
        return out;
    }

    private static Map<ItemCategory, List<String>> defaultPhrases() {
        Map<ItemCategory, List<String>> out = new EnumMap<>(ItemCategory.class);
        out.put(ItemCategory.FOOD, List.of(
                "there's nothing like a good meal after a long day",
                "I never say no to something good cooking nearby",
                "the way to my good side is through a decent meal"));
        out.put(ItemCategory.FLOWERS, List.of(
                "I always stop to notice a nice bloom",
                "a handful of fresh flowers can turn my whole day around",
                "I've a soft spot for anything that grows and blooms"));
        out.put(ItemCategory.ORES_GEMS, List.of(
                "there's something about a good gemstone that catches my eye",
                "I've always had a weakness for what glitters underground",
                "a bit of raw ore or a bright gem, and I'm yours for the afternoon"));
        out.put(ItemCategory.BOOKS_PAPER, List.of(
                "I could get lost in a good book for hours",
                "give me something to read and I'll forget the time entirely",
                "there's nothing quite like the smell of a fresh page"));
        out.put(ItemCategory.TOOLS, List.of(
                "a well-made tool is worth more than gold to me",
                "I notice good craftsmanship in a tool before almost anything else",
                "there's real satisfaction in something built to last"));
        out.put(ItemCategory.REDSTONE, List.of(
                "redstone contraptions -- don't ask me why, but I find them fascinating",
                "I could watch a clever little redstone rig click away all day",
                "something about wires and switches just gets my attention"));
        out.put(ItemCategory.DECORATION, List.of(
                "I like making a place feel like home, with the right little touches",
                "the right decoration can make a house feel like it belongs to someone",
                "I notice when a place has been decorated with care"));
        out.put(ItemCategory.MUSIC, List.of(
                "music -- even a simple tune -- brightens my whole day",
                "I've always had an ear for a good tune",
                "a bit of music nearby and I'm in a much better mood"));
        return out;
    }
}
