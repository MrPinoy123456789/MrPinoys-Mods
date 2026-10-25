package charter.food;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Eating, least nourishing first. The caller adds the step's drain to its
 * carried debt and asks for a plan; whole items come off only while the debt
 * covers the item at the front of the order, and the rest carries. Because the
 * front item never changes until it runs out, many small steps remove exactly
 * what one big step does.
 */
public final class EatPlan {

    private static final double EPS = 1e-9;

    /** Items eaten, the debt to carry, and the nourishment that could not be met because the store ran out. */
    public record Result(Map<String, Integer> eaten, double debt, double unmet) {
    }

    private EatPlan() {
    }

    /**
     * The eat order: nourishment ascending, then faster-spoiling shelf class first,
     * then item id. Items the table does not know are skipped.
     */
    public static List<String> order(Provisions provisions, FoodTable table) {
        List<String> ids = new ArrayList<>();
        for (String id : provisions.counts().keySet()) {
            if (table.isFood(id)) ids.add(id);
        }
        ids.sort(Comparator
                .comparingDouble((String id) -> table.get(id).nourishment())
                .thenComparing((String id) -> -table.get(id).shelf().ordinal())
                .thenComparing(Comparator.naturalOrder()));
        return ids;
    }

    /** Does not change the provisions; the caller applies {@link Result#eaten()}. */
    public static Result plan(Provisions provisions, double debt, FoodTable table) {
        Map<String, Integer> eaten = new TreeMap<>();
        double left = Math.max(0, debt);
        for (String id : order(provisions, table)) {
            double n = table.get(id).nourishment();
            int have = provisions.count(id);
            int take = Math.min(have, (int) Math.floor((left + EPS) / n));
            if (take > 0) {
                eaten.put(id, take);
                left -= take * n;
            }
            if (take < have) {
                return new Result(eaten, Math.max(0, left), 0);
            }
        }
        // Every food ran out; whatever debt is left is hunger nobody could feed.
        return new Result(eaten, 0, left > EPS ? left : 0);
    }
}
