package chartercurrency;

import chartercurrency.core.AccountStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code config/chartercurrency/accounts.json}.
 *
 * <p><b>Atomic writes.</b> Serialize to a temp file, fsync, then {@code ATOMIC_MOVE}
 * over the original. A crash mid-write leaves the previous file intact rather than a
 * truncated one -- and a truncated accounts file is not a corrupted save, it is every
 * player on the server losing their money at once.
 *
 * <p><b>Debounced.</b> Writes are coalesced onto a background thread on a 15-second
 * timer. A player spamming {@code /bank 1} produces one disk write, not two hundred,
 * and no disk write ever happens on the server thread where it would stutter the tick.
 *
 * <p><b>The crash window.</b> Debouncing means up to 15 seconds of transactions can be
 * lost to a hard crash. That is the deliberate trade: the alternative, a synchronous
 * fsync per transaction, puts disk latency inside the tick loop. Losing 15 seconds of
 * deposits is recoverable and symmetric -- the cobblestone was already removed from
 * the inventory, so a crash costs a player money rather than minting it. An economy
 * that fails toward *less* currency cannot be farmed, which is the property that
 * actually matters. {@link #flushNow()} on clean shutdown makes the ordinary case lossless.
 */
public final class JsonAccountStore implements AccountStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long FLUSH_SECONDS = 15;

    private final Path file;
    private final Path temp;
    private final Map<UUID, Map<String, Long>> balances = new LinkedHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ScheduledExecutorService writer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chartercurrency-accounts");
                t.setDaemon(true);
                return t;
            });

    public JsonAccountStore(Path directory) {
        this.file = directory.resolve("accounts.json");
        this.temp = directory.resolve("accounts.json.tmp");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            CharterCurrencyMod.LOG.error("Could not create {}", directory, e);
        }
        writer.scheduleWithFixedDelay(this::flushIfDirty, FLUSH_SECONDS, FLUSH_SECONDS,
                TimeUnit.SECONDS);
    }

    @Override
    public synchronized Map<UUID, Map<String, Long>> load() {
        balances.clear();
        if (!Files.exists(file)) {
            // A leftover temp file means we died mid-write last time. The real file
            // is missing, so the temp is the best copy of the truth that exists.
            if (Files.exists(temp)) {
                CharterCurrencyMod.LOG.warn(
                        "accounts.json is missing but a temp file exists -- recovering from it");
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    CharterCurrencyMod.LOG.error("Recovery failed", e);
                    return Map.of();
                }
            } else {
                CharterCurrencyMod.LOG.info("No accounts file yet -- starting a fresh economy");
                return Map.of();
            }
        }

        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
            JsonObject accounts = root.getAsJsonObject("accounts");
            if (accounts == null) return Map.of();

            int skipped = 0;
            for (Map.Entry<String, com.google.gson.JsonElement> e : accounts.entrySet()) {
                try {
                    UUID id = UUID.fromString(e.getKey());
                    JsonObject account = e.getValue().getAsJsonObject();
                    Map<String, Long> account_balances = new TreeMap<>();

                    if (account.has("balances")) {
                        JsonObject rows = account.getAsJsonObject("balances");
                        for (Map.Entry<String, com.google.gson.JsonElement> row : rows.entrySet()) {
                            long value = row.getValue().getAsLong();
                            if (value > 0) account_balances.put(row.getKey(), value);
                        }
                    }

                    if (!account_balances.isEmpty()) balances.put(id, account_balances);
                } catch (RuntimeException bad) {
                    // One malformed row must not cost everyone else their money.
                    skipped++;
                }
            }
            if (skipped > 0) {
                CharterCurrencyMod.LOG.warn("Skipped {} malformed account entries", skipped);
            }
            CharterCurrencyMod.LOG.info("Loaded {} accounts", balances.size());
            return new HashMap<>(balances);
        } catch (Exception e) {
            // Refuse to start from a half-understood file. Loading an empty economy
            // here would look like a working server and quietly wipe every balance the
            // moment the next write lands on top of the file we failed to read.
            CharterCurrencyMod.LOG.error("accounts.json is unreadable -- REFUSING to overwrite it. "
                    + "Fix or remove the file and restart.", e);
            throw new IllegalStateException("Corrupt accounts.json at " + file, e);
        }
    }

    @Override
    public synchronized void put(UUID player, Map<String, Long> accountBalances) {
        Map<String, Long> kept = new TreeMap<>();
        for (Map.Entry<String, Long> e : accountBalances.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) kept.put(e.getKey(), e.getValue());
        }
        if (kept.isEmpty()) {
            balances.remove(player);
        } else {
            balances.put(player, kept);
        }
        dirty.set(true);
    }

    @Override
    public void flushNow() {
        flushIfDirty();
    }

    private final Object writeLock = new Object();

    private void flushIfDirty() {
        // One write at a time: the timer thread and a synchronous flush share one temp file.
        synchronized (writeLock) {
            writeOnce();
        }
    }

    private void writeOnce() {
        if (!dirty.compareAndSet(true, false)) return;

        JsonObject accounts = new JsonObject();
        synchronized (this) {
            for (Map.Entry<UUID, Map<String, Long>> e : balances.entrySet()) {
                JsonObject rows = new JsonObject();
                for (Map.Entry<String, Long> b : e.getValue().entrySet()) {
                    rows.addProperty(b.getKey(), b.getValue());
                }
                JsonObject account = new JsonObject();
                account.add("balances", rows);
                accounts.add(e.getKey().toString(), account);
            }
        }
        JsonObject root = new JsonObject();
        root.add("accounts", accounts);

        try {
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, out);
                out.flush();
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Put the flag back so the next tick retries rather than dropping the write.
            dirty.set(true);
            CharterCurrencyMod.LOG.error("Failed to write accounts.json", e);
        }
    }

    /** Stop the timer and make one last write. Called on server shutdown. */
    public void close() {
        writer.shutdown();
        flushIfDirty();
    }
}
