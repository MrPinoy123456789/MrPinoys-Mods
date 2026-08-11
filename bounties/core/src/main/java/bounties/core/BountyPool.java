package bounties.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable pool of bounty definitions.
 */
public final class BountyPool {

    private final List<BountyDefinition> entries;

    public BountyPool(List<BountyDefinition> entries) {
        this.entries = List.copyOf(entries);
    }

    public static BountyPool empty() {
        return new BountyPool(List.of());
    }

    public static BountyPool of(BountyDefinition... entries) {
        List<BountyDefinition> list = new ArrayList<>(entries.length);
        Collections.addAll(list, entries);
        return new BountyPool(list);
    }

    public BountyDefinition at(int index) {
        return entries.get(index);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<BountyDefinition> entries() {
        return entries;
    }
}
