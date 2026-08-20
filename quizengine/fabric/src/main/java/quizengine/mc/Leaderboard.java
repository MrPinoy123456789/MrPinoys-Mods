package quizengine.mc;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only thing in this mod that survives a restart.
 *
 * <p>Mutation happens on the server main thread. The background writer takes a
 * synchronized immutable snapshot before serializing, then writes it atomically —
 * to a temp file, then renames over the original — so it never iterates live
 * server-thread state and a crash mid-write leaves the previous file intact.
 *
 * <p>Keyed by UUID, never by name. A rename must not reset someone's score.
 */
public final class Leaderboard {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long FLUSH_DELAY_SECONDS = 30;

    /** {@code name} is last-seen, for display only. */
    public record Entry(String name, int points, int wins) {}

    private final Path file;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final ScheduledExecutorService writer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "quizengine-leaderboard");
                t.setDaemon(true);
                return t;
            });

    public Leaderboard(Path file) {
        this.file = file;
        writer.scheduleWithFixedDelay(this::flushIfDirty,
                FLUSH_DELAY_SECONDS, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    // ---- reads ------------------------------------------------------------

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int pointsOf(UUID player) {
        Entry e = entries.get(player);
        return e == null ? 0 : e.points();
    }

    /** Highest first. */
    public synchronized List<Map.Entry<UUID, Entry>> top(int limit) {
        List<Map.Entry<UUID, Entry>> all = entries.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        all.sort(Comparator.comparingInt((Map.Entry<UUID, Entry> e) -> e.getValue().points())
                .reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    // ---- writes (main thread only) ----------------------------------------

    public synchronized void award(UUID player, String name, int points, boolean won) {
        Entry current = entries.getOrDefault(player, new Entry(name, 0, 0));
        entries.put(player, new Entry(
                name != null ? name : current.name(),
                current.points() + points,
                current.wins() + (won ? 1 : 0)));
        dirty.set(true);
    }

    /** Refresh the display name on join, without touching the score. */
    public synchronized void seen(UUID player, String name) {
        Entry current = entries.get(player);
        if (current != null && !current.name().equals(name)) {
            entries.put(player, new Entry(name, current.points(), current.wins()));
            dirty.set(true);
        }
    }

    // ---- persistence ------------------------------------------------------

    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Entry> raw = GSON.fromJson(r,
                    new TypeToken<Map<String, Entry>>() {}.getType());
            entries.clear();
            if (raw != null) {
                raw.forEach((id, entry) -> {
                    try {
                        entries.put(UUID.fromString(id), entry);
                    } catch (IllegalArgumentException ignored) {
                        QuizMod.LOG.warn("Skipping malformed leaderboard key: {}", id);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            QuizMod.LOG.error("Could not read leaderboard; starting empty", e);
        }
    }

    private void flushIfDirty() {
        if (dirty.compareAndSet(true, false) && !write(snapshot())) {
            dirty.set(true);
        }
    }

    /** Synchronous write, for server shutdown. */
    public void flushNow() {
        dirty.set(false);
        if (!write(snapshot())) {
            dirty.set(true);
        }
    }

    synchronized Map<String, Entry> snapshot() {
        Map<String, Entry> out = new LinkedHashMap<>();
        entries.forEach((id, entry) -> out.put(id.toString(), entry));
        return out;
    }

    private boolean write(Map<String, Entry> data) {
        synchronized (writeLock) {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.createDirectories(file.getParent());
                try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    GSON.toJson(data, w);
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (IOException e) {
                QuizMod.LOG.error("Failed to write leaderboard", e);
                return false;
            }
        }
    }

    public void shutdown() {
        writer.shutdown();
    }
}
