package kamutotems.core;

import java.util.HashMap;
import java.util.Map;

/** Mutable discovery ledger that only permits knowledge to advance to higher tiers. */
public final class DiscoveryBook {

    private final Map<String, DiscoveryTier> entries;

    public DiscoveryBook(Map<String, DiscoveryTier> initial) {
        this.entries = new HashMap<>(initial == null ? Map.of() : initial);
    }

    public DiscoveryTier tierOf(String key) {
        return entries.getOrDefault(key, DiscoveryTier.UNKNOWN);
    }

    /** Records a higher discovery tier, returning whether the ledger changed. */
    public boolean record(String key, DiscoveryTier tier) {
        DiscoveryTier current = entries.getOrDefault(key, DiscoveryTier.UNKNOWN);
        if (tier == null || tier.ordinal() <= current.ordinal()) {
            return false;
        }
        entries.put(key, tier);
        return true;
    }

    public Map<String, DiscoveryTier> snapshot() {
        return new HashMap<>(entries);
    }
}
