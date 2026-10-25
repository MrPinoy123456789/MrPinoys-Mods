package charter.food;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Item id to {@link FoodEntry}. Core takes the table as data; the fabric side
 * reads {@code food.json} and fills it. An id not in the table is not a
 * provision.
 */
public final class FoodTable {

    private final Map<String, FoodEntry> entries = new TreeMap<>();

    public FoodTable put(String itemId, double nourishment, ShelfClass shelf) {
        entries.put(itemId, new FoodEntry(nourishment, shelf));
        return this;
    }

    /** The entry for an item, or null when it is not a food. */
    public FoodEntry get(String itemId) {
        return entries.get(itemId);
    }

    public boolean isFood(String itemId) {
        return entries.containsKey(itemId);
    }

    public Map<String, FoodEntry> entries() {
        return Collections.unmodifiableMap(entries);
    }
}
