package charter.siege;

import charter.id.CoreId;

import java.util.Map;
import java.util.TreeMap;

/**
 * Food a carrier brought in through an open side (DESIGN.md §10.4): up to this
 * much of each item may go into the site's store until the siege clock passes
 * {@code expiresSiege}. A carrier is a player or a cart, named by the fabric side
 * ("player:uuid", "cart:uuid"). Entering again replaces the allowance.
 */
public final class Allowance {

    public final String carrier;
    public final CoreId site;
    public final long expiresSiege;
    final Map<String, Integer> items;

    public Allowance(String carrier, CoreId site, Map<String, Integer> items, long expiresSiege) {
        this.carrier = carrier;
        this.site = site;
        this.items = new TreeMap<>(items);
        this.expiresSiege = expiresSiege;
    }

    public int left(String itemId) {
        return items.getOrDefault(itemId, 0);
    }

    public Map<String, Integer> items() {
        return java.util.Collections.unmodifiableMap(new TreeMap<>(items));
    }

    void use(String itemId, int n) {
        items.merge(itemId, -n, Integer::sum);
        if (items.get(itemId) <= 0) items.remove(itemId);
    }
}
