package charter.mc.discord;

import charter.id.PlayerId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Which Discord account speaks for which player. A player proves both sides by
 * asking for a code in game (only they see it) and giving it to the bot from their
 * Discord account (only they can send as it). One Discord account per player and
 * one player per Discord account; a new link replaces the old one on either side.
 *
 * <p>Codes live in memory only: a restart forgets them, and a player asks again.
 * The links are saved in the world's {@code charter/} folder, beside the state.
 * Everything here runs on the server thread.
 */
public final class LinkBook {

    public static final String FILE_NAME = "discord-links.json";

    /** No 0, O, 1 or I: a code is read off a screen and typed by hand. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 6;
    /** Wrong codes a Discord account may send in one window before it must wait. */
    static final int MAX_WRONG = 5;
    static final long WRONG_WINDOW_MILLIS = 15 * 60_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Redeemed { LINKED, NO_SUCH_CODE, EXPIRED, SLOW_DOWN }

    /** What redeeming a code did, and for which player when it linked. */
    public record Result(Redeemed kind, PlayerId player) {
    }

    private record Pending(PlayerId player, long expiresAt) {
    }

    private record Wrong(int count, long since) {
    }

    private final Map<PlayerId, String> discordOf = new LinkedHashMap<>();
    private final Map<String, PlayerId> playerOf = new HashMap<>();
    private final Map<String, Pending> codes = new HashMap<>();
    private final Map<String, Wrong> wrong = new HashMap<>();
    private final RandomGenerator random;
    private final long codeLifetimeMillis;

    public LinkBook(RandomGenerator random, long codeLifetimeMillis) {
        this.random = random;
        this.codeLifetimeMillis = codeLifetimeMillis;
    }

    // ---- the links ----

    public Optional<String> discordOf(PlayerId player) {
        return Optional.ofNullable(discordOf.get(player));
    }

    public Optional<PlayerId> playerOf(String discordUser) {
        return Optional.ofNullable(playerOf.get(discordUser));
    }

    public int size() {
        return discordOf.size();
    }

    /** Links the two, dropping any older link either of them had. */
    public void link(PlayerId player, String discordUser) {
        unlink(player);
        unlinkDiscord(discordUser);
        discordOf.put(player, discordUser);
        playerOf.put(discordUser, player);
    }

    /** True if the player had a link. */
    public boolean unlink(PlayerId player) {
        String user = discordOf.remove(player);
        if (user == null) return false;
        playerOf.remove(user);
        return true;
    }

    /** True if the Discord account had a link. */
    public boolean unlinkDiscord(String discordUser) {
        PlayerId player = playerOf.remove(discordUser);
        if (player == null) return false;
        discordOf.remove(player);
        return true;
    }

    // ---- codes ----

    /** A fresh code for the player; any code they were given before stops working. */
    public String issue(PlayerId player, long now) {
        codes.values().removeIf(p -> p.player().equals(player) || p.expiresAt() <= now);
        String code;
        do {
            StringBuilder b = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = b.toString();
        } while (codes.containsKey(code));
        codes.put(code, new Pending(player, now + codeLifetimeMillis));
        return code;
    }

    /** The Discord account sends a code. A code works once. */
    public Result redeem(String discordUser, String typed, long now) {
        Wrong w = wrong.get(discordUser);
        if (w != null && now - w.since() >= WRONG_WINDOW_MILLIS) {
            wrong.remove(discordUser);
            w = null;
        }
        if (w != null && w.count() >= MAX_WRONG) return new Result(Redeemed.SLOW_DOWN, null);

        String code = normalise(typed);
        Pending pending = codes.get(code);
        if (pending == null) {
            wrong.put(discordUser, w == null ? new Wrong(1, now) : new Wrong(w.count() + 1, w.since()));
            return new Result(Redeemed.NO_SUCH_CODE, null);
        }
        codes.remove(code);
        if (pending.expiresAt() <= now) return new Result(Redeemed.EXPIRED, null);
        wrong.remove(discordUser);
        link(pending.player(), discordUser);
        return new Result(Redeemed.LINKED, pending.player());
    }

    /** Upper case, with spaces and dashes a player might add taken out. */
    static String normalise(String typed) {
        if (typed == null) return "";
        return typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    // ---- the file ----

    public void load(Path dir, Consumer<String> warn) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("links")) {
                JsonObject o = e.getAsJsonObject();
                link(new PlayerId(UUID.fromString(o.get("player").getAsString())), o.get("discord").getAsString());
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); Discord links start empty. The file was left as it is.");
            discordOf.clear();
            playerOf.clear();
        }
    }

    public void save(Path dir, Consumer<String> warn) {
        JsonArray links = new JsonArray();
        for (Map.Entry<PlayerId, String> e : discordOf.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("player", e.getKey().value().toString());
            o.addProperty("discord", e.getValue());
            links.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("links", links);
        Path file = dir.resolve(FILE_NAME);
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            warn.accept("Could not save " + file + ": " + e);
        }
    }
}
