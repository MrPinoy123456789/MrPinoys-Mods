package chartercurrency.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Per-player state that is not a balance: Mint allowance and tax counters now, earn
 * caps and probation later. The shape of {@code players.json}.
 *
 * <p>One lock guards the whole book; services built on it (the Mint, the earn
 * endpoint) synchronise through {@link #locked}. State is plain data so the fabric side
 * can write it as JSON.
 */
public final class PlayerBook {

    /** One player's record. */
    public static final class Player {
        public Mint.State mint;
        /** Per earn source: soft cap pool and what was earned today. */
        public Map<String, SourceDay> sources = new LinkedHashMap<>();
        /** The day {@code renownToday} was counted on, and the Renown generated that day. */
        public long renownDay;
        public long renownToday;
        /** The kingdom last resolved for this player ("" for none) and when first seen there (ms). */
        public String kingdom = "";
        public long kingdomSince;

        public Player() {}
    }

    /** One earn source's per-day record for one player. */
    public static final class SourceDay {
        public long day;
        /** Soft cap left: refilled by the soft cap each day, to at most three times it. */
        public long softRemaining;
        public long earnedToday;

        public SourceDay() {}
    }

    /** The file shape of {@code players.json}. */
    public static final class File {
        public Map<String, Player> players = new LinkedHashMap<>();
        /** Rolling daily totals, keyed {@code kind.dayNumber}. Pruned to a few weeks. */
        public Map<String, Long> stats = new TreeMap<>();
    }

    private final File file;
    private final Runnable onChange;
    private final Object lock = new Object();

    public PlayerBook(File initial, Runnable onChange) {
        this.file = initial == null ? new File() : initial;
        if (this.file.players == null) this.file.players = new LinkedHashMap<>();
        if (this.file.stats == null) this.file.stats = new TreeMap<>();
        this.onChange = onChange;
    }

    public PlayerBook() {
        this(new File(), () -> { });
    }

    /** The lock guarding the file; services that mutate records directly hold it. */
    Object monitor() {
        return lock;
    }

    public <T> T locked(Function<File, T> action) {
        synchronized (lock) {
            return action.apply(file);
        }
    }

    /** Call after mutating inside {@link #locked} so the file gets written. */
    public void changed() {
        onChange.run();
    }

    /** The record for a player, created empty if absent. Caller must hold the lock. */
    Player player(UUID id) {
        return file.players.computeIfAbsent(id.toString(), k -> new Player());
    }

    /** Add to a daily stat. Caller must hold the lock. */
    void addStat(String kind, long day, long amount) {
        file.stats.merge(kind + "." + day, amount, Long::sum);
        // Keep three weeks; older keys are dropped as new days arrive.
        file.stats.keySet().removeIf(k -> {
            int dot = k.lastIndexOf('.');
            if (dot < 0) return false;
            try {
                return Long.parseLong(k.substring(dot + 1)) < day - 21;
            } catch (NumberFormatException e) {
                return false;
            }
        });
    }

    /** Sum of a stat over the last {@code days} days ending today. */
    public long stat(String kind, long today, int days) {
        synchronized (lock) {
            long sum = 0;
            for (int i = 0; i < days; i++) {
                sum += file.stats.getOrDefault(kind + "." + (today - i), 0L);
            }
            return sum;
        }
    }
}
