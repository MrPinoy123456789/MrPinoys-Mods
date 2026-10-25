package charter.food;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** What a granary or camp store holds: item id to count. Sorted by id so iteration is deterministic. */
public final class Provisions {

    private final Map<String, Integer> counts = new TreeMap<>();

    public int count(String itemId) {
        return counts.getOrDefault(itemId, 0);
    }

    public Provisions add(String itemId, int n) {
        if (n < 0) throw new IllegalArgumentException("n must not be negative");
        if (n > 0) counts.merge(itemId, n, Integer::sum);
        return this;
    }

    /** Removes up to n and returns how many were actually removed. */
    public int remove(String itemId, int n) {
        int have = count(itemId);
        int taken = Math.min(have, Math.max(0, n));
        if (taken == have) counts.remove(itemId);
        else if (taken > 0) counts.put(itemId, have - taken);
        return taken;
    }

    public Provisions apply(Map<String, Integer> removed) {
        removed.forEach(this::remove);
        return this;
    }

    public Map<String, Integer> counts() {
        return Collections.unmodifiableMap(counts);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public int totalItems() {
        int total = 0;
        for (int c : counts.values()) total += c;
        return total;
    }

    /** Total nourishment of the items the table knows; other items count for nothing. */
    public double nourishment(FoodTable table) {
        double total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            FoodEntry f = table.get(e.getKey());
            if (f != null) total += f.nourishment() * e.getValue();
        }
        return total;
    }

    public Provisions copy() {
        Provisions p = new Provisions();
        p.counts.putAll(counts);
        return p;
    }
}
