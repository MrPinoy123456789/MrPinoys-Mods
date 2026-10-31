package charter.mc.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The few Discord HTTP calls the bot makes, on the JDK's own client: no library to
 * ship. Every call is asynchronous, so none of them ever holds up the server thread.
 * A 429 is waited out and tried once more; anything else that fails is logged.
 */
public final class DiscordRest {

    static final String API = "https://discord.com/api/v10";
    private static final String USER_AGENT = "DiscordBot (https://github.com/MrPinoy123456789/MrPinoys-Mods, 1)";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String token;
    private final Consumer<String> warn;

    public DiscordRest(String token, Consumer<String> warn) {
        this.token = token;
        this.warn = warn;
    }

    // ---- the calls ----

    /** Replaces the bot's slash commands, in one server when {@code guildId} is set, else everywhere. */
    public CompletableFuture<JsonElement> putCommands(String applicationId, String guildId, JsonElement commands) {
        String path = guildId == null || guildId.isBlank()
                ? "/applications/" + applicationId + "/commands"
                : "/applications/" + applicationId + "/guilds/" + guildId.strip() + "/commands";
        return call("PUT", path, commands, true);
    }

    /** Acknowledges an interaction at once, so the three second window never runs out while the server thread works. */
    public CompletableFuture<JsonElement> defer(String interactionId, String interactionToken, boolean updateMessage) {
        JsonObject body = new JsonObject();
        // 6: the reply will edit the message the button is on. 5: a new reply follows, seen only by the user.
        body.addProperty("type", updateMessage ? 6 : 5);
        if (!updateMessage) {
            JsonObject data = new JsonObject();
            data.addProperty("flags", DiscordMessages.EPHEMERAL);
            body.add("data", data);
        }
        return call("POST", "/interactions/" + interactionId + "/" + interactionToken + "/callback", body, false);
    }

    /** Puts the real reply in place of the acknowledgement. */
    public CompletableFuture<JsonElement> editReply(String applicationId, String interactionToken, JsonObject message) {
        return call("PATCH", "/webhooks/" + applicationId + "/" + interactionToken + "/messages/@original", message, false);
    }

    /** Sends a direct message to a Discord account. Fails quietly (logged) if they do not take DMs from the server's members. */
    public CompletableFuture<JsonElement> directMessage(String userId, JsonObject message) {
        JsonObject open = new JsonObject();
        open.addProperty("recipient_id", userId);
        return call("POST", "/users/@me/channels", open, true).thenCompose(channel -> {
            if (channel == null || !channel.isJsonObject() || !channel.getAsJsonObject().has("id")) {
                return CompletableFuture.completedFuture(null);
            }
            return call("POST", "/channels/" + channel.getAsJsonObject().get("id").getAsString() + "/messages", message, true);
        });
    }

    // ---- plumbing ----

    private CompletableFuture<JsonElement> call(String method, String path, JsonElement body, boolean authed) {
        return send(method, path, body, authed, true);
    }

    private CompletableFuture<JsonElement> send(String method, String path, JsonElement body, boolean authed, boolean mayRetry) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
        // Interaction callbacks and webhook edits are authorised by the interaction token in the path.
        if (authed) b.header("Authorization", "Bot " + token);
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            int status = response.statusCode();
            if (status == 429 && mayRetry) {
                long waitMillis = retryAfterMillis(response.body());
                return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(waitMillis, TimeUnit.MILLISECONDS))
                        .thenCompose(x -> send(method, path, body, authed, false));
            }
            if (status / 100 != 2) {
                warn.accept("Discord " + method + " " + redact(path) + " answered " + status + ": " + clip(response.body()));
                return CompletableFuture.completedFuture(null);
            }
            String text = response.body();
            return CompletableFuture.completedFuture(text == null || text.isBlank() ? null : JsonParser.parseString(text));
        }).exceptionally(e -> {
            warn.accept("Discord " + method + " " + redact(path) + " failed: " + e.getMessage());
            return null;
        });
    }

    static long retryAfterMillis(String body) {
        try {
            double seconds = JsonParser.parseString(body).getAsJsonObject().get("retry_after").getAsDouble();
            return Math.max(100, Math.min(60_000, (long) (seconds * 1000)));
        } catch (RuntimeException unreadable) {
            return 1_000;
        }
    }

    /** Interaction tokens sit in some paths; they never reach the log. */
    static String redact(String path) {
        String[] parts = path.split("/");
        if (parts.length > 3 && (parts[1].equals("interactions") || parts[1].equals("webhooks"))) parts[3] = "...";
        return String.join("/", parts);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
