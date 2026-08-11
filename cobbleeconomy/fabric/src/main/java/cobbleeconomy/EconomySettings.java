package cobbleeconomy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    public static EconomySettings load(Path directory) {
        Path file = directory.resolve("settings.json");
        EconomySettings settings = new EconomySettings();

        if (!Files.exists(file)) {
            settings.save(file);
            return settings;
        }

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
        } catch (Exception e) {
            CobbleEconomyMod.LOG.warn("Could not read settings.json; using defaults", e);
        }
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

        JsonObject root = new JsonObject();
        root.add("leaderboards", boards);
        root.add("logging", logging);

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
