package bounties;

import bounties.core.AcceptedBounty;
import bounties.core.PlayerBounties;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code config/bounties/state.json}.
 *
 * <p>Per-player held bounties, written atomically with a temp file + rename.
 * Mutated on the server thread, flushed on a background timer.
 */
public final class BountyState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long FLUSH_SECONDS = 15;

    private final Path file;
    private final Path temp;
    private final Map<UUID, PlayerBounties> players = new LinkedHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ScheduledExecutorService writer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bounties-state");
                t.setDaemon(true);
                return t;
            });

    public BountyState(Path directory) {
        this.file = directory.resolve("state.json");
        this.temp = directory.resolve("state.json.tmp");
        writer.scheduleWithFixedDelay(this::flushIfDirty, FLUSH_SECONDS, FLUSH_SECONDS,
                TimeUnit.SECONDS);
    }

    public synchronized PlayerBounties of(UUID player) {
        return players.computeIfAbsent(player, id -> new PlayerBounties());
    }

    public synchronized void set(UUID player, PlayerBounties state) {
        players.put(player, state);
        dirty.set(true);
    }

    public synchronized void load() {
        if (!Files.exists(file)) {
            // Recover from a leftover temp file if the server died mid-write.
            if (Files.exists(temp)) {
                BountyMod.LOG.warn("state.json is missing but a temp file exists -- recovering from it");
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    BountyMod.LOG.error("Recovery failed", e);
                    return;
                }
            } else {
                BountyMod.LOG.info("No state file yet -- starting empty");
                return;
            }
        }

        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, List<AcceptedBounty>> raw = GSON.fromJson(r,
                    new TypeToken<Map<String, List<AcceptedBounty>>>() {}.getType());
            players.clear();
            if (raw != null) {
                raw.forEach((id, held) -> {
                    try {
                        players.put(UUID.fromString(id), new PlayerBounties(held));
                    } catch (IllegalArgumentException ignored) {
                        BountyMod.LOG.warn("Skipping malformed state key: {}", id);
                    }
                });
            }
            BountyMod.LOG.info("Loaded {} bounty states", players.size());
        } catch (IOException | RuntimeException e) {
            // Refuse to start from a half-understood file: overwriting it would wipe progress.
            BountyMod.LOG.error("state.json is unreadable -- REFUSING to overwrite it. "
                    + "Fix or remove the file and restart.", e);
            throw new IllegalStateException("Corrupt state.json at " + file, e);
        }
    }

    public void flushNow() {
        flushIfDirty();
    }

    public void shutdown() {
        writer.shutdown();
        flushIfDirty();
    }

    private void flushIfDirty() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        write();
    }

    private void write() {
        Map<String, List<AcceptedBounty>> out = new LinkedHashMap<>();
        synchronized (this) {
            players.forEach((id, state) -> out.put(id.toString(), state.held()));
        }

        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, w);
                w.flush();
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dirty.set(true);
            BountyMod.LOG.error("Failed to write state.json", e);
        }
    }
}
