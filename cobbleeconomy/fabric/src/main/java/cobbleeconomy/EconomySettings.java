package cobbleeconomy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code config/cobbleeconomy/settings.json}: the knobs a server owner turns.
 *
 * <p>Small on purpose. Every setting here is one the spec explicitly asks to be
 * configurable, and nothing else -- a config file full of options nobody changes is
 * just more surface for a typo to break the server on.
 */
public final class EconomySettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Whether {@code /baltop} exists at all. */
    public boolean leaderboardsEnabled = true;

    /** Whether joining players get the wealth snapshot. Independent of the above. */
    public boolean showOnLogin = true;

    /** How many rows the login snapshot shows per currency. The spec's default is 3. */
    public int loginTopCount = 3;

    /** Ticks to wait after join before sending it, so it lands after the join spam. */
    public int loginDelayTicks = 40;

    /** Whether {@code /balance} includes each currency's rank. */
    public boolean showRankOnBalance = true;

    /** Whether transactions are appended to transactions.log. */
    public boolean transactionLogFile = true;

    public final WelcomeSettings welcome = new WelcomeSettings();

    public final ShopSettings shop = new ShopSettings();

    /**
     * Settings for the shop's purchase confirmation.
     *
     * <p>Diamonds are the premium currency and there is no way to buy them back, so a
     * misclick on a 20-diamond elytra costs a player something they spent hours
     * earning. A large cobblestone spend is recoverable by mining, but a 12,800
     * cobblestone shift-click is still not something anyone should do by accident.
     */
    public static final class ShopSettings {

        /**
         * Per-currency confirmation thresholds, keyed by currency id. A purchase whose
         * total cost in that currency reaches the threshold asks first. {@code 0}, or a
         * currency that is absent from the map, never asks.
         *
         * <p>The diamond default is 1 on purpose: every diamond purchase confirms,
         * because there is no such thing as a cheap one.
         */
        public Map<String, Long> confirmAt = defaultThresholds();

        /** The threshold for a currency, or 0 if it never needs confirming. */
        public long confirmAt(String currencyId) {
            Long threshold = confirmAt.get(currencyId.toLowerCase(Locale.ROOT));
            return threshold == null ? 0 : threshold;
        }

        private static Map<String, Long> defaultThresholds() {
            // LinkedHashMap rather than Map.of: this is written back out to settings.json
            // and a randomised order would rewrite the file differently every restart.
            Map<String, Long> out = new LinkedHashMap<>();
            out.put("cobblestone", 1_000L);
            out.put("diamond", 1L);
            return out;
        }
    }

    /** Settings for the first-join welcome message. */
    public static final class WelcomeSettings {
        public boolean enabled = true;
        public int delayTicks = 40;
        public List<String> lines = List.of(
                "Two things are money here.",
                "  \uD83E\uDEA8 Cobblestone \u2014 common. Bank it with /bank all",
                "  \uD83D\uDC8E Diamond \u2014 premium. Earned from quizzes, bounties and riddles",
                "Type /shop to see what the server sells.",
                "  [ Open the shop ]");
    }

    public static EconomySettings load(Path directory) {
        Path file = directory.resolve("settings.json");
        EconomySettings settings = new EconomySettings();

        if (!Files.exists(file)) {
            settings.save(file);
            return settings;
        }

        // Set when a block this version knows about was missing from an older file, so
        // the owner gets the new knobs written out with their defaults rather than
        // having to find out from the README that they exist.
        boolean rewrite = false;

        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
            JsonObject boards = root.getAsJsonObject("leaderboards");
            if (boards != null) {
                settings.leaderboardsEnabled = bool(boards, "enabled", settings.leaderboardsEnabled);
                settings.showOnLogin = bool(boards, "showOnLogin", settings.showOnLogin);
                settings.loginTopCount = Math.max(1,
                        integer(boards, "loginTopCount", settings.loginTopCount));
                settings.loginDelayTicks = Math.max(0,
                        integer(boards, "loginDelayTicks", settings.loginDelayTicks));
                settings.showRankOnBalance =
                        bool(boards, "showRankOnBalance", settings.showRankOnBalance);
            }
            JsonObject logging = root.getAsJsonObject("logging");
            if (logging != null) {
                settings.transactionLogFile =
                        bool(logging, "transactionFile", settings.transactionLogFile);
            }
            JsonObject welcome = root.getAsJsonObject("welcome");
            if (welcome != null) {
                settings.welcome.enabled = bool(welcome, "enabled", settings.welcome.enabled);
                settings.welcome.delayTicks = Math.max(0,
                        integer(welcome, "delayTicks", settings.loginDelayTicks));
                JsonArray lines = welcome.getAsJsonArray("lines");
                if (lines != null) {
                    List<String> parsed = new ArrayList<>();
                    for (JsonElement e : lines) {
                        parsed.add(e.getAsString());
                    }
                    if (!parsed.isEmpty()) {
                        settings.welcome.lines = parsed;
                    }
                }
            } else {
                settings.welcome.delayTicks = settings.loginDelayTicks;
            }
            JsonObject shop = root.getAsJsonObject("shop");
            if (shop == null) {
                rewrite = true;
            } else {
                JsonObject confirm = shop.getAsJsonObject("confirmAt");
                if (confirm != null) {
                    // Replaced wholesale rather than merged. An owner who writes
                    // {"diamond": 0} means "stop asking about diamonds", and merging
                    // over the defaults would leave the cobblestone prompt they never
                    // asked for -- and no way at all to turn the whole thing off.
                    Map<String, Long> parsed = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> line : confirm.entrySet()) {
                        parsed.put(line.getKey().toLowerCase(Locale.ROOT),
                                Math.max(0, line.getValue().getAsLong()));
                    }
                    settings.shop.confirmAt = parsed;
                }
            }
        } catch (Exception e) {
            CobbleEconomyMod.LOG.warn("Could not read settings.json; using defaults", e);
            return settings;
        }

        if (rewrite) settings.save(file);
        return settings;
    }

    private void save(Path file) {
        JsonObject boards = new JsonObject();
        boards.addProperty("enabled", leaderboardsEnabled);
        boards.addProperty("showOnLogin", showOnLogin);
        boards.addProperty("loginTopCount", loginTopCount);
        boards.addProperty("loginDelayTicks", loginDelayTicks);
        boards.addProperty("showRankOnBalance", showRankOnBalance);

        JsonObject logging = new JsonObject();
        logging.addProperty("transactionFile", transactionLogFile);

        JsonObject welcome = new JsonObject();
        welcome.addProperty("enabled", this.welcome.enabled);
        welcome.addProperty("delayTicks", this.welcome.delayTicks);
        JsonArray lines = new JsonArray();
        for (String line : this.welcome.lines) {
            lines.add(line);
        }
        welcome.add("lines", lines);

        JsonObject confirmAt = new JsonObject();
        for (Map.Entry<String, Long> line : this.shop.confirmAt.entrySet()) {
            confirmAt.addProperty(line.getKey(), line.getValue());
        }
        JsonObject shop = new JsonObject();
        shop.add("confirmAt", confirmAt);

        JsonObject root = new JsonObject();
        root.add("leaderboards", boards);
        root.add("logging", logging);
        root.add("welcome", welcome);
        root.add("shop", shop);

        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, out);
        } catch (Exception e) {
            CobbleEconomyMod.LOG.warn("Could not write settings.json", e);
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }
}
