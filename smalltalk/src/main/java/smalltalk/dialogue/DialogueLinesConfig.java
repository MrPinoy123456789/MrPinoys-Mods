package smalltalk.dialogue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import smalltalk.SmallTalkMod;
import smalltalk.identity.Personality;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * personality -&gt; context id -&gt; line pool, at
 * {@code config/smalltalk/lines.json}. Same readOrCreate contract as
 * {@code SmallTalkConfig}. This is where community content plugs in today
 * (see the note on {@link ContextEvaluator} about the datapack this isn't,
 * yet).
 */
public final class DialogueLinesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Map<Personality, Map<String, List<String>>> lines = defaults();

    private DialogueLinesConfig() {}

    public static void load(Path configDir) {
        Path file = configDir.resolve(SmallTalkMod.MOD_ID).resolve("lines.json");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(toJson(defaults()), w);
                }
                SmallTalkMod.LOG.info("Created default smalltalk/lines.json");
                lines = defaults();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(r, JsonObject.class);
                if (parsed == null) {
                    throw new IllegalStateException("smalltalk/lines.json is empty");
                }
                lines = fromJson(parsed);
            }
        } catch (Exception e) {
            SmallTalkMod.LOG.error("smalltalk/lines.json could not be read; using defaults and "
                    + "leaving the file alone", e);
            lines = defaults();
        }
    }

    public static List<String> linesFor(Personality personality, String contextId) {
        return lines.getOrDefault(personality, Map.of()).getOrDefault(contextId, List.of());
    }

    private static Map<Personality, Map<String, List<String>>> fromJson(JsonObject root) {
        Map<Personality, Map<String, List<String>>> out = new EnumMap<>(Personality.class);
        for (Map.Entry<String, JsonElement> personalityEntry : root.entrySet()) {
            Personality personality;
            try {
                personality = Personality.valueOf(personalityEntry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!personalityEntry.getValue().isJsonObject()) {
                continue;
            }
            Map<String, List<String>> contexts = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> contextEntry : personalityEntry.getValue().getAsJsonObject().entrySet()) {
                if (!contextEntry.getValue().isJsonArray()) {
                    continue;
                }
                List<String> pool = new ArrayList<>();
                contextEntry.getValue().getAsJsonArray().forEach(el -> pool.add(el.getAsString()));
                contexts.put(contextEntry.getKey(), pool);
            }
            out.put(personality, contexts);
        }
        return out.isEmpty() ? defaults() : out;
    }

    private static JsonObject toJson(Map<Personality, Map<String, List<String>>> lines) {
        JsonObject root = new JsonObject();
        for (Map.Entry<Personality, Map<String, List<String>>> personalityEntry : lines.entrySet()) {
            JsonObject contexts = new JsonObject();
            for (Map.Entry<String, List<String>> contextEntry : personalityEntry.getValue().entrySet()) {
                JsonArray arr = new JsonArray();
                contextEntry.getValue().forEach(arr::add);
                contexts.add(contextEntry.getKey(), arr);
            }
            root.add(personalityEntry.getKey().name(), contexts);
        }
        return root;
    }

    private static Map<Personality, Map<String, List<String>>> defaults() {
        Map<Personality, Map<String, List<String>>> out = new EnumMap<>(Personality.class);
        out.put(Personality.CHIPPER, Map.of(
                "player_on_fire", List.of("YOU'RE ON FIRE. Not that I'm panicking. Are YOU panicking?"),
                "first_meeting", List.of("Oh, hello! I don't think we've met -- what a lovely surprise!"),
                "thunder", List.of("Isn't the thunder just thrilling? Like the sky's applauding!"),
                "rain", List.of("Rain! Perfect weather for staying in and chatting, don't you think?"),
                "night", List.of("Still up? I love a good starry night, honestly."),
                "generic", List.of("Lovely to see you! What's new?")));
        out.put(Personality.GRUFF, Map.of(
                "player_on_fire", List.of("You're burning. Might want to see to that."),
                "first_meeting", List.of("Don't think I know you. Suppose I do now."),
                "thunder", List.of("Storm's loud tonight."),
                "rain", List.of("Rain again. Figures."),
                "night", List.of("Bit late to be wandering, isn't it."),
                "generic", List.of("Yeah. What do you want.")));
        out.put(Personality.DREAMY, Map.of(
                "player_on_fire", List.of("You're glowing... oh, that's actual fire. You should probably deal with that."),
                "first_meeting", List.of("Have we met before? In another life, maybe. Anyway, hello."),
                "thunder", List.of("The thunder sounds like something enormous, turning over in its sleep."),
                "rain", List.of("I like how the rain makes everything sound far away."),
                "night", List.of("The stars are doing that thing again where they look like they're listening."),
                "generic", List.of("Mm? Oh -- hello. I was somewhere else for a moment.")));
        out.put(Personality.BRISK, Map.of(
                "player_on_fire", List.of("You are on fire. Water, now, then we can talk."),
                "first_meeting", List.of("New face. Name and business, if you please."),
                "thunder", List.of("Storm. Best make this quick."),
                "rain", List.of("Rain's picking up. Say what you came to say."),
                "night", List.of("Late. Keep it brief."),
                "generic", List.of("Yes? Make it quick, I've things to do.")));
        out.put(Personality.BASHFUL, Map.of(
                "player_on_fire", List.of("Oh! Oh no, you're -- you're on fire, um, you should--"),
                "first_meeting", List.of("Oh! I -- hello. Sorry. I didn't see you there."),
                "thunder", List.of("The thunder's a bit much, isn't it. I don't -- I don't love it."),
                "rain", List.of("Rain. Um. I like it, actually. Quietly."),
                "night", List.of("It's late. I should probably... yes. Goodnight, I suppose."),
                "generic", List.of("Oh -- um, hello.")));
        out.put(Personality.SMUG, Map.of(
                "player_on_fire", List.of("You're on fire. I'd offer to help, but this is rather entertaining."),
                "first_meeting", List.of("Ah, a new admirer. I get that a lot."),
                "thunder", List.of("Even the sky's making an entrance. Amateur."),
                "rain", List.of("Rain ruins everyone's hair but mine, somehow."),
                "night", List.of("Out at night? Bold. I approve."),
                "generic", List.of("Well, look who it is.")));
        return out;
    }
}
