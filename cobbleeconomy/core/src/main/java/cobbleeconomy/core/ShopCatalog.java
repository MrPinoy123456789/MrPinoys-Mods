package cobbleeconomy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The set of things the server sells. Loaded from config, never hard-coded.
 *
 * <p>Prices live here rather than in Java because the server owner has to be able to
 * retune the economy without a rebuild -- and they will, constantly, because the first
 * pass at prices is always wrong. Section 23 of the spec is the point: {@code 64 Ice =
 * 640 Cobblestone} is a decision, not a calculation, and decisions belong in config.
 *
 * <p>Insertion order is preserved so the {@code /shop} listing matches the order the
 * owner wrote the file in.
 */
public final class ShopCatalog {

    private final Map<String, ShopEntry> entries = new LinkedHashMap<>();

    public void put(ShopEntry entry) {
        entries.put(entry.key().toLowerCase(Locale.ROOT), entry);
    }

    public boolean remove(String key) {
        return entries.remove(key.toLowerCase(Locale.ROOT)) != null;
    }

    public Optional<ShopEntry> find(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return Optional.ofNullable(entries.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    /** Only entries a player can actually buy. */
    public List<ShopEntry> available() {
        List<ShopEntry> out = new ArrayList<>();
        for (ShopEntry entry : entries.values()) {
            if (entry.enabled() && entry.isValid()) out.add(entry);
        }
        return out;
    }

    /** Everything, including disabled and malformed entries. For admin listings. */
    public List<ShopEntry> all() {
        return List.copyOf(entries.values());
    }

    /** Category headings in the order they first appear. */
    public List<String> categories() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ShopEntry entry : available()) out.add(entry.category());
        return List.copyOf(out);
    }

    public List<ShopEntry> inCategory(String category) {
        List<ShopEntry> out = new ArrayList<>();
        for (ShopEntry entry : available()) {
            if (entry.category().equals(category)) out.add(entry);
        }
        return out;
    }

    /** Buyable keys, for tab completion. */
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        for (ShopEntry entry : available()) out.add(entry.key());
        return out;
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
