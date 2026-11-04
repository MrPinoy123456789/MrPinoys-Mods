package chartercurrency;

import chartercurrency.core.Analytics;
import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import chartercurrency.core.EarnService;
import chartercurrency.core.MintService;
import chartercurrency.core.Treasury;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Analytics on disk (SPEC section 10): {@code analytics/events-YYYY-MM-DD.jsonl}, one line
 * for every movement {@link TransactionLog} records, and {@code analytics/snapshots.jsonl},
 * one line a day. The line format and the maths are {@link Analytics}, under test.
 *
 * <p>Like the transaction log, writes go through a bounded queue onto a background thread,
 * so disk never reaches the tick. Analytics is never allowed to touch gameplay: every
 * failure is caught, logged once, and the game carries on as if nothing was asked.
 */
final class AnalyticsRecorder {

    /** How often the tick looks for a new day: every 10 seconds. */
    private static final int ROLLOVER_CHECK_TICKS = 200;

    private final Path dir;
    private final Services s;
    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(4096);
    private final Thread writer;
    private volatile boolean running = true;
    private volatile boolean enabled;
    private volatile boolean warned;
    /** The day the next rollover snapshot is for. Server thread only. */
    private long day = Long.MIN_VALUE;

    AnalyticsRecorder(Path configDir, Services services, boolean enabled) {
        this.dir = configDir.resolve("analytics");
        this.s = services;
        this.enabled = enabled;
        writer = new Thread(this::drain, "chartercurrency-analytics");
        writer.setDaemon(true);
        writer.start();
    }

    Path directory() {
        return dir;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    // ---- events ----

    /** Records one event line. Never throws. */
    void record(Analytics.Event event) {
        if (!enabled || event == null) return;
        try {
            long ts = System.currentTimeMillis();
            String kingdom = event.hasKingdom() ? null : kingdomOf(event.playerId());
            String line = event.toJson(ts, kingdom);
            LocalDate on = Instant.ofEpochMilli(ts).atZone(s.settings.zone()).toLocalDate();
            Path file = dir.resolve(Analytics.eventFile(on));
            offer(() -> append(file, line));
        } catch (RuntimeException e) {
            warnOnce("Could not record an analytics event", e);
        }
    }

    private String kingdomOf(UUID player) {
        if (player == null) return null;
        try {
            return s.kingdoms.source().kingdomOf(player).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- snapshots ----

    /** Server started: a snapshot of today now, unless today already has one. */
    void started(MinecraftServer server) {
        day = s.settings.today();
        if (!enabled) return;
        try {
            if (!hasSnapshot(LocalDate.ofEpochDay(day))) capture(server, day, "startup");
        } catch (RuntimeException e) {
            warnOnce("Could not take the startup analytics snapshot", e);
        }
    }

    /** Every tick: at a new day, a snapshot of the day that ended. */
    void tick(MinecraftServer server) {
        if (server.getTickCount() % ROLLOVER_CHECK_TICKS != 0) return;
        long today = s.settings.today();
        if (day == Long.MIN_VALUE) {
            day = today;
            return;
        }
        if (today == day) return;
        long ended = day;
        day = today;
        if (!enabled) return;
        try {
            capture(server, ended, "rollover");
        } catch (RuntimeException e) {
            warnOnce("Could not take the daily analytics snapshot", e);
        }
    }

    private boolean hasSnapshot(LocalDate on) {
        Path file = dir.resolve(Analytics.SNAPSHOT_FILE);
        if (!Files.exists(file)) return false;
        String needle = "\"day\":\"" + on + "\"";
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains(needle)) return true;
            }
        } catch (IOException e) {
            warnOnce("Could not read " + file, e);
            return true; // unreadable: better no startup line than one on every restart
        }
        return false;
    }

    /**
     * Reads the numbers on the server thread, so they are one moment's, then hands the
     * rest to the writer: it adds up the day's event lines (all written by then, since
     * the queue keeps order) and appends the snapshot line.
     */
    private void capture(MinecraftServer server, long onDay, String reason) {
        Currency coin = s.coin();
        long banked = s.economy.totalSupply(coin);
        long treasuries = s.treasury.totalSupply();

        long carried = 0;
        int online = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online++;
            for (Denomination d : coin.denominations()) {
                long n = CoinOps.carried(p, d) * d.value();
                carried = carried > Long.MAX_VALUE - n ? Long.MAX_VALUE : carried + n;
            }
        }

        List<Analytics.Holding> holdings = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : s.economy.balances(coin).entrySet()) {
            holdings.add(new Analytics.Holding(e.getKey(), s.names.nameOf(e.getKey()), e.getValue()));
        }

        long valorInAccounts = s.currencies.byId("valor").map(s.economy::totalSupply).orElse(0L);
        Map<String, Long> valorBySource = new LinkedHashMap<>();
        if (s.earn != null) {
            for (EarnService.Source src : s.earn.config().sources().values()) {
                long earned = s.book.stat("valor-" + src.key(), onDay, 1);
                if (earned > 0) valorBySource.put(src.key(), earned);
            }
        }

        List<Analytics.KingdomRow> kingdoms = new ArrayList<>();
        for (String id : s.treasury.orgIds()) {
            String name;
            try {
                name = Treasury.COMMONS.equals(id) ? "Commons" : s.kingdoms.source().displayName(id);
            } catch (RuntimeException e) {
                name = id;
            }
            kingdoms.add(new Analytics.KingdomRow(id, name, s.treasury.balance(id),
                    s.treasury.taxCollected(id), s.treasury.renown(id)));
        }

        LocalDate date = LocalDate.ofEpochDay(onDay);
        Analytics.SnapshotInput input = new Analytics.SnapshotInput(System.currentTimeMillis(), date, reason,
                banked, treasuries, carried, online, holdings,
                s.book.stat(MintService.STAT_MINTED, onDay, 1),
                s.book.stat(MintService.STAT_TAX_PAID, onDay, 1),
                s.book.stat(MintService.STAT_TAX_DESTROYED, onDay, 1),
                valorInAccounts, valorBySource, kingdoms);
        offer(() -> {
            Analytics.DayFlows flows = readFlows(dir.resolve(Analytics.eventFile(date)));
            append(dir.resolve(Analytics.SNAPSHOT_FILE), Analytics.snapshot(input, flows));
        });
    }

    /** A day's event lines added up. A line that does not parse is skipped. */
    private Analytics.DayFlows readFlows(Path file) {
        Analytics.DayFlows flows = new Analytics.DayFlows();
        if (!Files.exists(file)) return flows;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonElement parsed = JsonParser.parseString(line);
                    if (parsed.isJsonObject() && plain(parsed) instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> event = (Map<String, Object>) map;
                        flows.add(event);
                    }
                } catch (RuntimeException skip) {
                    // A torn last line after a crash; the rest still count.
                }
            }
        } catch (IOException e) {
            warnOnce("Could not read " + file, e);
        }
        return flows;
    }

    /** A parsed JSON value as plain maps, lists, strings, longs, doubles and booleans. */
    static Object plain(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e instanceof JsonObject o) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> m : o.entrySet()) out.put(m.getKey(), plain(m.getValue()));
            return out;
        }
        if (e instanceof JsonArray a) {
            List<Object> out = new ArrayList<>();
            for (JsonElement x : a) out.add(plain(x));
            return out;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) {
            java.math.BigDecimal n = p.getAsBigDecimal();
            try {
                return n.longValueExact();
            } catch (ArithmeticException notWhole) {
                return n.doubleValue();
            }
        }
        return p.getAsString();
    }

    /** The last {@code count} snapshot lines, oldest first. */
    List<JsonObject> history(int count) throws IOException {
        Path file = dir.resolve(Analytics.SNAPSHOT_FILE);
        List<JsonObject> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = Math.max(0, lines.size() - count); i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            try {
                JsonElement parsed = JsonParser.parseString(line);
                if (parsed.isJsonObject()) out.add(parsed.getAsJsonObject());
            } catch (RuntimeException skip) {
                // a torn line is not worth failing the command for
            }
        }
        return out;
    }

    // ---- the writer ----

    private void offer(Runnable task) {
        if (!running) return;
        if (!queue.offer(task)) warnOnce("Analytics queue full; dropped a line", null);
    }

    private void append(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            warnOnce("Could not write " + file, e);
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                Runnable task = queue.poll(1, TimeUnit.SECONDS);
                if (task == null) continue;
                try {
                    task.run();
                } catch (RuntimeException e) {
                    warnOnce("An analytics write failed", e);
                }
            } catch (InterruptedException e) {
                if (running) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void warnOnce(String what, Throwable cause) {
        if (warned) return;
        warned = true;
        if (cause == null) {
            CharterCurrencyMod.LOG.warn("{}. Analytics carries on; this is said only once.", what);
        } else {
            CharterCurrencyMod.LOG.warn("{}. Analytics carries on; this is said only once.", what, cause);
        }
    }

    void close() {
        if (!running) return;
        running = false;
        writer.interrupt();
        try {
            writer.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
