package hearsay.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Operator-written dialogue pools (SPEC.md §6.1). Pool keys are
 * {@code "<profession>.<moment>"}, falling back to the bare {@code <moment>}
 * pool when no profession-specific pool exists.
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

    /** The pool key for one moment of one profession, e.g. {@code minecraft:farmer.ambient}. */
    public static String key(String professionId, String moment) {
        return professionId + "." + moment;
    }

    /** Share of lines drawn from a profession's own pool when both pools exist. */
    public static final double DEFAULT_PROFESSION_CHANCE = 0.5;

    /**
     * A line for one moment of one profession, blended with the shared pool of the
     * same name at {@link #DEFAULT_PROFESSION_CHANCE}.
     */
    public String pickFor(String professionId, String moment, Random random) {
        return pickFor(professionId, moment, random, DEFAULT_PROFESSION_CHANCE);
    }

    /**
     * A line for one moment of one profession.
     *
     * <p>When the profession has its own pool <em>and</em> a shared pool of the same
     * name exists, {@code professionChance} decides which is drawn from, so a farmer
     * keeps the generic village chatter in rotation instead of only ever speaking
     * farmer lines. With only one of the two present that pool is used outright, so
     * a profession without special lines is never mute.
     *
     * <p>Each pool keeps its own shuffle bag, so blending does not disturb the
     * without-replacement guarantee within either one.
     *
     * @param professionChance 0.0 = always shared, 1.0 = always the profession's own
     */
    public String pickFor(String professionId, String moment, Random random, double professionChance) {
        String specific = key(professionId, moment);
        boolean hasSpecific = has(specific);
        boolean hasShared = has(moment);

        if (hasSpecific && hasShared) {
            return random.nextDouble() < professionChance
                    ? pick(specific, random)
                    : pick(moment, random);
        }
        if (hasSpecific) {
            return pick(specific, random);
        }
        return pick(moment, random);
    }

    /**
     * Fills in pools the operator's file does not mention, from {@code fallback}.
     *
     * <p>Purely in memory -- the file on disk is never touched, per the suite's
     * never-overwrite rule. Merges missing <em>pools</em>, not lines within an
     * existing pool.
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
