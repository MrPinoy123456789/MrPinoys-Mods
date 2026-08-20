package dailyquests;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who has done today's quest, and how many days running.
 *
 * <p>Only two things need to survive a restart: the last day each player completed,
 * and their streak. Everything else is derived — today's quest comes from the date,
 * and "have they done it yet" is a comparison rather than a stored flag.
 *
 * <p>Mutated on the server thread only; written atomically on a background thread.
 */
public final class DailyState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final long FLUSH_DELAY_SECONDS = 20;

    /**
     * @param name       last seen, for display only
     * @param lastDay    day key of their most recent completion
     * @param streak     consecutive days including {@code lastDay}
     * @param totalDone  lifetime completions, for bragging rights
     */
    public record Entry(String name, String lastDay, int streak, int totalDone) {}

    private final Path file;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ScheduledExecutorService writer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dailyquests-state");
                t.setDaemon(true);
                return t;
            });

    public DailyState(Path file) {
        this.file = file;
        writer.scheduleWithFixedDelay(this::flushIfDirty,
                FLUSH_DELAY_SECONDS, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    // ---- the day ----------------------------------------------------------

    /**
     * Today's key, shifted so the day turns over at the configured UTC hour rather
     * than at midnight. Real time rather than world time: the world clock stops when
     * the server is down and jumps when someone sleeps, neither of which should move
     * a daily.
     */
    public static String dayKey(int rolloverHourUtc) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).minusHours(rolloverHourUtc);
        return now.toLocalDate().format(DAY);
    }

    /**
     * Whether a turn-in on {@code today} keeps the run alive. The gap is measured in
     * days rather than required to be exactly one, so a player who misses a day does
     * not lose a month of history — the streak counts turn-ins, and the grace window
     * decides how long one can be left standing on its own.
     */
    public static boolean continuesStreak(String previousDay, String today, int graceDays) {
        try {
            long gap = ChronoUnit.DAYS.between(
                    LocalDate.parse(previousDay, DAY), LocalDate.parse(today, DAY));
            return gap >= 1 && gap <= graceDays;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** How many more days the player can skip before the streak resets; 0 means today is the last chance. */
    public static int daysOfGraceLeft(String previousDay, String today, int graceDays) {
        try {
            long gap = ChronoUnit.DAYS.between(
                    LocalDate.parse(previousDay, DAY), LocalDate.parse(today, DAY));
            return (int) Math.max(0, graceDays - gap);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---- reads ------------------------------------------------------------

    public boolean hasCompleted(UUID player, String dayKey) {
        Entry e = entries.get(player);
        return e != null && dayKey.equals(e.lastDay());
    }

    public Entry entryOf(UUID player) {
        return entries.get(player);
    }

    public int streakOf(UUID player) {
        Entry e = entries.get(player);
        return e == null ? 0 : e.streak();
    }

    public int completedToday(String dayKey) {
        return (int) entries.values().stream()
                .filter(e -> dayKey.equals(e.lastDay()))
                .count();
    }

    /**
     * Returns entries sorted by streak (desc), then total done (desc), for a
     * paginated streak board.
     */
    public List<Entry> topByStreak(int offset, int limit) {
        return entries.values().stream()
                .sorted((a, b) -> {
                    int byStreak = Integer.compare(b.streak(), a.streak());
                    if (byStreak != 0) return byStreak;
                    return Integer.compare(b.totalDone(), a.totalDone());
                })
                .skip(offset)
                .limit(limit)
                .toList();
    }

    // ---- writes -----------------------------------------------------------

    /**
     * Records a completion and returns the new streak. A gap wider than the grace
     * window resets to 1 rather than continuing, which is the whole point of a streak.
     */
    public int complete(UUID player, String name, String dayKey, int graceDays) {
        Entry current = entries.get(player);
        int streak = 1;
        int total = 1;
        if (current != null) {
            total = current.totalDone() + 1;
            if (continuesStreak(current.lastDay(), dayKey, graceDays)) {
                streak = current.streak() + 1;
            }
        }
        entries.put(player, new Entry(name, dayKey, streak, total));
        dirty.set(true);
        return streak;
    }

    public void seen(UUID player, String name) {
        Entry current = entries.get(player);
        if (current != null && !current.name().equals(name)) {
            entries.put(player, new Entry(name, current.lastDay(),
                    current.streak(), current.totalDone()));
            dirty.set(true);
        }
    }

    // ---- persistence ------------------------------------------------------

    /** Loads all valid player entries, skipping malformed UUID keys. */
    public void load() {
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
                        DailyQuestsMod.LOG.warn("Skipping malformed key: {}", id);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            DailyQuestsMod.LOG.error("Could not read daily state; starting empty", e);
        }
    }

    private void flushIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            write();
        }
    }

    /** Writes a snapshot immediately, regardless of the dirty flag. */
    public void flushNow() {
        dirty.set(false);
        write();
    }

    private void write() {
        Map<String, Entry> out = new LinkedHashMap<>();
        entries.forEach((id, entry) -> out.put(id.toString(), entry));

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            DailyQuestsMod.LOG.error("Failed to write daily state", e);
        }
    }

    public int size() {
        return entries.size();
    }

    public void shutdown() {
        writer.shutdown();
    }
}
