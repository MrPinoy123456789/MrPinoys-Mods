package chatdonkey;

import chatdonkey.core.Behaviors;
import chatdonkey.core.DefaultLines;
import chatdonkey.core.EventDefinition;
import chatdonkey.core.EventTuning;
import chatdonkey.core.EventPool;
import chatdonkey.core.LinePools;
import chatdonkey.core.ReadOrCreate;
import chatdonkey.core.Settings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code settings.json} and {@code lines.json} from
 * {@code config/chatdonkey/}, writing defaults on first boot.
 *
 * <p>The semantics themselves live in {@link ReadOrCreate} in {@code core} --
 * this class only supplies the Gson lambdas, so the never-overwrite-a-broken-file
 * rule is tested without Minecraft on the classpath.
 */
public final class DonkeyConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Type LINES_TYPE = new TypeToken<Map<String, List<String>>>() {}.getType();

    private static final Type EVENTS_TYPE =
            new TypeToken<Map<String, EventTuning>>() {}.getType();

    private final Path dir;

    private Settings settings = Settings.defaults();
    private LinePools lines = LinePools.empty();
    private EventPool events = EventPool.defaults();

    public DonkeyConfig(Path dir) {
        this.dir = dir;
    }

    public Settings settings() {
        return settings;
    }

    public LinePools lines() {
        return lines;
    }

    public EventPool events() {
        return events;
    }

    /** Reloads all operator files, retaining defaults in memory for missing or invalid data. */
    public void reload() {
        ReadOrCreate.Result<Settings> loadedSettings = ReadOrCreate.load(
                dir.resolve("settings.json"),
                Settings.defaults(),
                text -> GSON.fromJson(text, Settings.class),
                value -> GSON.toJson(value),
                ChatDonkeyMod.LOG::info);
        settings = loadedSettings.value().sanitised();

        LinePools defaults = new LinePools(DefaultLines.pools());
        ReadOrCreate.Result<Map<String, List<String>>> loadedLines = ReadOrCreate.load(
                dir.resolve("lines.json"),
                DefaultLines.pools(),
                text -> GSON.fromJson(text, LINES_TYPE),
                value -> GSON.toJson(value),
                ChatDonkeyMod.LOG::info);
        // Pools the operator's file does not mention fall back to stock, in
        // memory only -- their file is never rewritten.
        lines = new LinePools(loadedLines.value()).withDefaults(defaults);

        ReadOrCreate.Result<Map<String, EventTuning>> loadedEvents = ReadOrCreate.load(
                dir.resolve("events.json"),
                EventPool.defaults().asMap(),
                text -> GSON.fromJson(text, EVENTS_TYPE),
                value -> GSON.toJson(value),
                ChatDonkeyMod.LOG::info);
        events = buildPool(loadedEvents.value());

        ChatDonkeyMod.LOG.info("Chat donkey config loaded: {} line pools, {} events, {}",
                lines.size(), events.size(), settings.enabled() ? "enabled" : "DISABLED");
    }

    /**
     * Turns the parsed file into a pool, keying each entry's behavior from its
     * map key so an operator cannot silently desync the two.
     *
     * <p>An events file that leaves nothing usable falls back to the built-in
     * pool: a chat donkey mod with no events is just a config file, and the
     * operator has already been warned about each entry that was dropped.
     */
    private EventPool buildPool(Map<String, EventTuning> raw) {
        List<EventDefinition> definitions = new ArrayList<>();
        if (raw != null) {
            for (Map.Entry<String, EventTuning> entry : raw.entrySet()) {
                EventTuning value = entry.getValue();
                if (value == null) {
                    continue;
                }
                definitions.add(value.toDefinition(entry.getKey()));
            }
        }

        EventPool pool = EventPool.of(definitions, Behaviors.ids(), ChatDonkeyMod.LOG::warn);
        if (pool.isEmpty()) {
            ChatDonkeyMod.LOG.warn("events.json left no usable events -- using the built-in pool");
            return EventPool.defaults();
        }
        return pool;
    }
}
