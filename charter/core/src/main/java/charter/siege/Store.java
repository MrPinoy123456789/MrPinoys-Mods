package charter.siege;

import charter.food.Provisions;

import java.util.Map;
import java.util.TreeMap;

/**
 * A granary or camp store as the rules see it: the food in it and the fractional
 * debts carried between steps (PLAN.md §4.2). While its chunk is loaded the
 * fabric side keeps {@link #provisions} in step with the real chest; while it is
 * unloaded this is authoritative and removals are queued for the next load.
 */
public final class Store {

    public Provisions provisions = new Provisions();
    /** Nourishment owed to the eaters, not yet covered by a whole item. */
    public double eatDebt;
    /** Per item type, the part of an item owed to spoilage. */
    public final Map<String, Double> spoilDebts = new TreeMap<>();
}
