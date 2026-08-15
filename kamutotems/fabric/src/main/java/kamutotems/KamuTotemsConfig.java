package kamutotems;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config for the whole mod, one file at {@code config/kamutotems/config.json},
 * split into named sections ("totem", "boss", "quest").
 *
 * <p>The suite's readOrCreate contract, ordered by damage (SUITE_AUDIT section 4.2):
 * <ul>
 *   <li>Missing -&gt; write defaults, log it, return defaults.</li>
 *   <li><b>Fails to parse -&gt; return defaults but NEVER write.</b> An operator's
 *       broken-but-recoverable edit is worth more than a tidy file.</li>
 *   <li>Parses -&gt; return it.</li>
 * </ul>
 *
 * <p>Lookups never throw. A missing section or key yields the caller's fallback,
 * so a half-written config degrades to defaults key by key rather than all at
 * once.
 */
public final class KamuTotemsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static JsonObject root = defaults();
    private static Path file;

    private KamuTotemsConfig() {}

    public static void load(Path configDir) {
        file = configDir.resolve("config.json");
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaults(), w);
                }
                KamuTotemsMod.LOG.info("Created default config.json");
                root = defaults();
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(r, JsonObject.class);
                if (parsed == null) {
                    throw new IllegalStateException("config.json is empty");
                }
                root = parsed;
            }
        } catch (Exception e) {
            // Defaults in memory, file untouched. Do not rewrite an edit we
            // could not understand -- the operator can still fix it by hand.
            KamuTotemsMod.LOG.error("config.json could not be read; using defaults "
                    + "and leaving the file alone", e);
            root = defaults();
        }
    }

    public static void reload() {
        if (file != null) {
            load(file.getParent());
        }
    }

    public static JsonObject section(String name) {
        if (root.has(name) && root.get(name).isJsonObject()) {
            return root.getAsJsonObject(name);
        }
        return new JsonObject();
    }

    public static int i(String section, String key, int fallback) {
        try {
            JsonObject s = section(section);
            return s.has(key) ? s.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static double d(String section, String key, double fallback) {
        try {
            JsonObject s = section(section);
            return s.has(key) ? s.get(key).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean b(String section, String key, boolean fallback) {
        try {
            JsonObject s = section(section);
            return s.has(key) ? s.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static String s(String section, String key, String fallback) {
        try {
            JsonObject sec = section(section);
            return sec.has(key) ? sec.get(key).getAsString() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static JsonObject defaults() {
        JsonObject r = new JsonObject();

        // --- totem (SPEC.md section 5.8, section 6.4) -----------------------
        JsonObject totem = new JsonObject();
        totem.addProperty("max_charges", 16);
        // Imbue/remove costs in cobblestone, by tier. If play shows players
        // hesitating to experiment, halve these -- a strangled discovery loop
        // is a far worse failure than a weak sink (SPEC.md section 5.8).
        totem.addProperty("imbue_t1", 32);
        totem.addProperty("imbue_t2", 96);
        totem.addProperty("imbue_t3", 256);
        totem.addProperty("remove_t1", 16);
        totem.addProperty("remove_t2", 48);
        totem.addProperty("remove_t3", 128);
        totem.addProperty("kamu_item", "minecraft:amethyst_shard");
        totem.addProperty("reaction_depth_cap", 4);
        totem.addProperty("tier_multiplier_1", 1.0);
        totem.addProperty("tier_multiplier_2", 1.6);
        totem.addProperty("tier_multiplier_3", 2.5);
        r.add("totem", totem);

        // --- boss (SPEC.md section 7) --------------------------------------
        JsonObject boss = new JsonObject();
        boss.addProperty("mob", "minecraft:zombie");
        boss.addProperty("kamu_item", "minecraft:amethyst_shard");
        boss.addProperty("no_summon_radius", 24);
        // health = health_base * health_growth^tier  -> 80 / 160 / 320 / 640
        boss.addProperty("health_base", 40.0);
        boss.addProperty("health_growth", 2.0);
        // damage multiplier = damage_base + damage_step * tier -> 1.8x .. 4.2x,
        // with a flat floor in Boss.java so a low-base mob is still dangerous.
        boss.addProperty("damage_base", 1.0);
        boss.addProperty("damage_step", 0.8);
        r.add("boss", boss);

        // --- quest (SPEC.md section 8) -------------------------------------
        JsonObject quest = new JsonObject();
        // ON by default -- it is built, and a host that ships switched off is a
        // host nobody tests. The `dailyquests` collision is handled where it
        // actually occurs: KamuTotemsMod detects that mod at runtime and stands
        // down. Setting this false is an explicit override, not the safety net.
        quest.addProperty("enabled", true);
        quest.addProperty("rollover_hour_utc", 0);
        quest.addProperty("announce_on_join", true);
        // Deliberately higher than the dailyquests mod this replaces, whose
        // cap of 3 stopped the streak growing on day three.
        quest.addProperty("reward_base", 2);
        quest.addProperty("reward_per_day", 1);
        quest.addProperty("reward_cap", 16);
        // Paid per finished segment, so the chain rewards progress as it
        // happens rather than banking everything until the last step.
        quest.addProperty("reward_per_segment", 1);
        // The last step hands over the free daily boss as an item.
        quest.addProperty("grant_daily_sigil", true);
        r.add("quest", quest);

        return r;
    }
}
