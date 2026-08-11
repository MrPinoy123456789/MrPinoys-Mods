package chatdonkey.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Every player-visible string, keyed by pool (SPEC.md section 8). Pool keys are
 * {@code "<behavior>.<moment>"} for per-event lines and a bare word for the
 * shared pools -- {@code "lecture.during"}, {@code "hit"}, {@code "names"}.
 *
 * <p>A missing or empty pool yields an empty string rather than throwing. The
 * donkey going quiet is the correct failure direction for a joke mod; a crash
 * is not (DESIGN.md section 9.5).
 */
public final class LinePools {

    private final Map<String, List<String>> pools;

    public LinePools(Map<String, List<String>> pools) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (pools != null) {
            for (Map.Entry<String, List<String>> entry : pools.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                List<String> lines = new ArrayList<>();
                for (String line : entry.getValue()) {
                    if (line != null && !line.isBlank()) {
                        lines.add(line);
                    }
                }
                copy.put(entry.getKey(), Collections.unmodifiableList(lines));
            }
        }
        this.pools = Collections.unmodifiableMap(copy);
    }

    public static LinePools empty() {
        return new LinePools(Map.of());
    }

    public List<String> pool(String key) {
        return pools.getOrDefault(key, List.of());
    }

    public boolean has(String key) {
        return !pool(key).isEmpty();
    }

    public int size() {
        return pools.size();
    }

    /** A random line from the pool, or {@code ""} if the pool is missing or empty. */
    public String pick(String key, Random random) {
        List<String> lines = pool(key);
        if (lines.isEmpty()) {
            return "";
        }
        return lines.get(random.nextInt(lines.size()));
    }

    /** The pool key for one moment of one behavior, e.g. {@code lecture.open}. */
    public static String key(String behaviorId, String moment) {
        return behaviorId + "." + moment;
    }

    /**
     * A line for one moment of one behavior, falling back to the shared pool of
     * the same name.
     *
     * <p>{@code foodcritic.exit_bribed} first, then a bare {@code exit_bribed}.
     * Without this, every behavior needs a pool for every reachable ending or
     * the donkey silently says nothing -- an easy thing to miss, since the gap
     * only shows up when a player takes an unusual exit from a particular event.
     */
    public String pickFor(String behaviorId, String moment, Random random) {
        String line = pick(key(behaviorId, moment), random);
        return line.isEmpty() ? pick(moment, random) : line;
    }

    /**
     * Fills in pools the operator's file does not mention, from {@code fallback}.
     *
     * <p>Purely in memory -- the file on disk is never touched, per the suite's
     * never-overwrite rule. This is what stops a later milestone's new pools from
     * coming up silently empty on a server whose {@code lines.json} was generated
     * by an earlier one: an operator who edited their lines keeps every edit, and
     * still gets defaults for pools that did not exist when they edited.
     */
    public LinePools withDefaults(LinePools fallback) {
        if (fallback == null) {
            return this;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>(pools);
        for (Map.Entry<String, List<String>> entry : fallback.pools.entrySet()) {
            if (!has(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return new LinePools(merged);
    }
}
