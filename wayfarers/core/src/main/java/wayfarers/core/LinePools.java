package wayfarers.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Operator-written dialogue pools (SPEC.md §9.3). Pool keys are
 * {@code "<encounter>.<moment>"}.
 *
 * <p>A missing or empty pool yields an empty string rather than throwing. The
 * fallback path keeps the shared pool so an unusual moment is never mute.
 *
 * <p>Draws are without replacement within a pool; the bag is reshuffled only
 * when the pool is exhausted (SPEC.md §5.3 shuffle bag).
 */
public final class LinePools {

    private final Map<String, List<String>> pools;
    private final Map<String, ArrayDeque<String>> bags = new HashMap<>();

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

    /** A line drawn from the pool's shuffle bag, or {@code ""} if the pool is missing or empty. */
    public String pick(String key, Random random) {
        List<String> lines = pool(key);
        if (lines.isEmpty()) {
            return "";
        }
        ArrayDeque<String> bag = bags.computeIfAbsent(key, k -> new ArrayDeque<>(lines));
        if (bag.isEmpty()) {
            List<String> fresh = new ArrayList<>(lines);
            Collections.shuffle(fresh, random);
            bag.addAll(fresh);
        }
        return bag.isEmpty() ? "" : bag.pollFirst();
    }

    /** The pool key for one moment of one encounter, e.g. {@code lost_trader.open}. */
    public static String key(String encounterId, String moment) {
        return encounterId + "." + moment;
    }

    /**
     * A line for one moment of one encounter, falling back to the shared pool of
     * the same name.
     */
    public String pickFor(String encounterId, String moment, Random random) {
        String line = pick(key(encounterId, moment), random);
        return line.isEmpty() ? pick(moment, random) : line;
    }

    /**
     * Fills in pools the operator's file does not mention, from {@code fallback}.
     *
     * <p>Purely in memory -- the file on disk is never touched, per the suite's
     * never-overwrite rule.
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
