package charter.mc.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code config/charter/discord.json}: the Discord bot, off until an operator turns
 * it on. Read the suite's way: a missing file is written with the defaults, a file
 * that does not parse is left alone and Discord stays off.
 *
 * <p>The token can come from the {@code CHARTER_DISCORD_TOKEN} environment variable
 * instead of the file, so it never has to sit in a config folder that gets copied
 * around. The token is never logged.
 */
public final class DiscordConfig {

    public static final String FILE_NAME = "discord.json";
    public static final String TOKEN_ENV = "CHARTER_DISCORD_TOKEN";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Nothing connects to Discord until this is true. */
    public boolean enabled = false;
    /** The bot's token from the Discord developer portal (Bot page). Blank: read CHARTER_DISCORD_TOKEN. */
    public String botToken = "";
    /**
     * The Discord server (guild) id. Set: the bot's commands appear there at once.
     * Blank: they are registered for every server the bot is in and in DMs, which
     * Discord can take up to an hour to show.
     */
    public String guildId = "";
    /** Message a linked member on Discord when someone asks to join a faction they can take players in to. */
    public boolean notifyJoinRequests = true;
    /** How long a link code from {@code /charter discord link} works. */
    public int linkCodeMinutes = 10;

    /** The token to use: the file's, else the environment's. Blank when neither has one. */
    public String token(Function<String, String> env) {
        if (botToken != null && !botToken.isBlank()) return botToken.strip();
        String fromEnv = env.apply(TOKEN_ENV);
        return fromEnv == null ? "" : fromEnv.strip();
    }

    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (linkCodeMinutes < 1 || linkCodeMinutes > 24 * 60) out.add("linkCodeMinutes must be 1 to 1440, was " + linkCodeMinutes);
        if (guildId != null && !guildId.isBlank() && !guildId.strip().matches("\\d{5,25}")) {
            out.add("guildId must be the server's numeric id (Developer Mode, right click the server, Copy Server ID), was " + guildId);
        }
        return out;
    }

    public static DiscordConfig readOrCreate(Path dir, Consumer<String> info, Consumer<String> warn) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            DiscordConfig defaults = new DiscordConfig();
            try {
                Files.createDirectories(dir);
                Files.writeString(file, GSON.toJson(defaults) + "\n", StandardCharsets.UTF_8);
                info.accept("Wrote " + file + "; Discord stays off until it is enabled there.");
            } catch (IOException e) {
                warn.accept("Could not write " + file + " (" + e.getMessage() + "); Discord stays off.");
            }
            return defaults;
        }
        DiscordConfig read;
        try {
            read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DiscordConfig.class);
        } catch (IOException | JsonParseException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); Discord stays off. The file was left as it is.");
            return new DiscordConfig();
        }
        if (read == null) return new DiscordConfig();
        List<String> problems = read.problems();
        if (!problems.isEmpty()) {
            for (String p : problems) warn.accept(file + ": " + p);
            warn.accept("Discord stays off until " + file + " is fixed.");
            return new DiscordConfig();
        }
        return read;
    }
}
