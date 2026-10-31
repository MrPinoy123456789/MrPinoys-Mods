package charter.mc.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The bot's connection to Discord's gateway, on the JDK's own WebSocket: say hello,
 * keep the heartbeat, hand every interaction to the listener, and come back after a
 * drop (resuming the session when Discord allows it). It asks for no intents, since
 * interactions arrive without any: the bot never reads a channel.
 *
 * <p>All of its own work runs on one daemon thread; the listener is called there and
 * must hand game work to the server thread itself.
 */
public final class DiscordGateway {

    /** What the gateway reports. */
    public interface Listener {
        /** Connected and identified; called again after every fresh session. */
        void ready(String applicationId, String botName);

        /** A slash command or a button click. */
        void interaction(JsonObject interaction);
    }

    static final String GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";

    /** Close codes after which trying again cannot help: a bad token, bad intents, a bad shard. */
    static final Set<Integer> FATAL = Set.of(4004, 4010, 4011, 4012, 4013, 4014);
    /** Close codes after which the session cannot be resumed. */
    static final Set<Integer> FRESH = Set.of(4007, 4009);

    private static final long BACKOFF_MIN_MILLIS = 2_000;
    private static final long BACKOFF_MAX_MILLIS = 120_000;

    private final String token;
    private final Listener listener;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Charter Discord");
        t.setDaemon(true);
        return t;
    });

    // Touched only on the gateway's own thread.
    private WebSocket socket;
    private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
    private ScheduledFuture<?> heartbeat;
    private boolean acked = true;
    private Long sequence;
    private String sessionId;
    private String resumeUrl;
    private long backoff = BACKOFF_MIN_MILLIS;
    private int generation;
    private volatile boolean stopped;

    public DiscordGateway(String token, Listener listener, Consumer<String> info, Consumer<String> warn) {
        this.token = token;
        this.listener = listener;
        this.info = info;
        this.warn = warn;
    }

    public void start() {
        thread.execute(this::connect);
    }

    public void stop() {
        stopped = true;
        thread.execute(() -> {
            cancelHeartbeat();
            if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
            socket = null;
        });
        thread.shutdown();
    }

    // ---- connecting ----

    private void connect() {
        if (stopped) return;
        int gen = ++generation;
        String url = sessionId != null && resumeUrl != null ? resumeUrl + "/?v=10&encoding=json" : GATEWAY;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(url), new Socket(gen))
                .whenComplete((ws, error) -> thread.execute(() -> {
                    if (gen != generation) {
                        if (ws != null) ws.abort();
                        return;
                    }
                    if (error != null) {
                        warn.accept("Could not reach Discord: " + error.getMessage());
                        retryLater();
                    } else {
                        socket = ws;
                    }
                }));
    }

    /** Drops the connection (if any) and connects again after the back off. */
    private void retryLater() {
        if (stopped) return;
        generation++;
        cancelHeartbeat();
        if (socket != null) socket.abort();
        socket = null;
        long wait = backoff + ThreadLocalRandom.current().nextLong(1_000);
        backoff = Math.min(BACKOFF_MAX_MILLIS, backoff * 2);
        thread.schedule(this::connect, wait, TimeUnit.MILLISECONDS);
    }

    private void forgetSession() {
        sessionId = null;
        resumeUrl = null;
        sequence = null;
    }

    // ---- the protocol ----

    private void onPayload(JsonObject payload) {
        int op = payload.get("op").getAsInt();
        JsonElement d = payload.get("d");
        switch (op) {
            case 0 -> dispatch(payload, d);
            case 1 -> sendHeartbeat();
            case 7 -> retryNow();
            case 9 -> {
                boolean resumable = d != null && d.isJsonPrimitive() && d.getAsBoolean();
                if (!resumable) forgetSession();
                retryLater();
            }
            case 10 -> hello(d.getAsJsonObject().get("heartbeat_interval").getAsLong());
            case 11 -> acked = true;
            default -> {
                // Opcodes the bot does not use.
            }
        }
    }

    private void retryNow() {
        backoff = BACKOFF_MIN_MILLIS;
        retryLater();
    }

    private void hello(long interval) {
        cancelHeartbeat();
        acked = true;
        long first = (long) (interval * ThreadLocalRandom.current().nextDouble());
        heartbeat = thread.scheduleAtFixedRate(() -> {
            if (!acked) {
                warn.accept("Discord stopped answering heartbeats; reconnecting.");
                retryNow();
                return;
            }
            acked = false;
            sendHeartbeat();
        }, first, interval, TimeUnit.MILLISECONDS);

        JsonObject out = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        if (sessionId != null) {
            out.addProperty("op", 6);
            data.addProperty("session_id", sessionId);
            if (sequence != null) data.addProperty("seq", sequence);
            else data.add("seq", com.google.gson.JsonNull.INSTANCE);
        } else {
            out.addProperty("op", 2);
            data.addProperty("intents", 0);
            JsonObject props = new JsonObject();
            props.addProperty("os", System.getProperty("os.name", "linux"));
            props.addProperty("browser", "charter");
            props.addProperty("device", "charter");
            data.add("properties", props);
        }
        out.add("d", data);
        send(out);
    }

    private void dispatch(JsonObject payload, JsonElement d) {
        if (payload.has("s") && !payload.get("s").isJsonNull()) sequence = payload.get("s").getAsLong();
        String type = payload.has("t") && !payload.get("t").isJsonNull() ? payload.get("t").getAsString() : "";
        switch (type) {
            case "READY" -> {
                JsonObject ready = d.getAsJsonObject();
                sessionId = ready.get("session_id").getAsString();
                resumeUrl = ready.has("resume_gateway_url") ? ready.get("resume_gateway_url").getAsString() : null;
                backoff = BACKOFF_MIN_MILLIS;
                String app = ready.getAsJsonObject("application").get("id").getAsString();
                String name = ready.getAsJsonObject("user").get("username").getAsString();
                safely(() -> listener.ready(app, name));
            }
            case "RESUMED" -> {
                backoff = BACKOFF_MIN_MILLIS;
                info.accept("Discord session resumed.");
            }
            case "INTERACTION_CREATE" -> safely(() -> listener.interaction(d.getAsJsonObject()));
            default -> {
                // No intents: little else arrives, and none of it matters here.
            }
        }
    }

    private void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            warn.accept("Discord handler failed: " + e);
        }
    }

    private void sendHeartbeat() {
        JsonObject out = new JsonObject();
        out.addProperty("op", 1);
        if (sequence == null) out.add("d", com.google.gson.JsonNull.INSTANCE);
        else out.addProperty("d", sequence);
        send(out);
    }

    /** One frame at a time: the JDK's WebSocket refuses a send while another is in flight. */
    private void send(JsonObject payload) {
        WebSocket ws = socket;
        if (ws == null) return;
        String text = payload.toString();
        sending = sending.handle((x, e) -> null).thenCompose(x -> ws.sendText(text, true));
    }

    private void cancelHeartbeat() {
        if (heartbeat != null) heartbeat.cancel(false);
        heartbeat = null;
    }

    /** One connection's frames. Every callback moves to the gateway thread and is dropped if a newer connection exists. */
    private final class Socket implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder partial = new StringBuilder();

        Socket(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket ws) {
            thread.execute(() -> {
                if (gen == generation) socket = ws;
            });
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                thread.execute(() -> {
                    if (gen != generation || stopped) return;
                    try {
                        onPayload(JsonParser.parseString(text).getAsJsonObject());
                    } catch (RuntimeException e) {
                        warn.accept("Could not read a Discord gateway message: " + e);
                    }
                });
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            thread.execute(() -> {
                if (gen != generation || stopped) return;
                if (FATAL.contains(code)) {
                    warn.accept("Discord closed the connection (" + code + " " + reason + "). "
                            + (code == 4004 ? "The bot token is wrong. " : "") + "Discord stays off until the server restarts.");
                    stopped = true;
                    cancelHeartbeat();
                    return;
                }
                if (FRESH.contains(code)) forgetSession();
                retryLater();
            });
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            thread.execute(() -> {
                if (gen != generation || stopped) return;
                warn.accept("Discord connection error: " + error.getMessage());
                retryLater();
            });
        }
    }
}
