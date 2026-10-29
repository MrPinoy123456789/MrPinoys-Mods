package charter.siege;

import charter.engine.ActionEngine;
import charter.food.FoodEntry;
import charter.food.FoodTable;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.land.Core;
import charter.state.CharterState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Getting food in (DESIGN.md section 10.4). Food goes into a keep's granary or an outpost's store through the
 * granary, and anything on the food table is taken. A keep is never besieged (protected keeps), so there is no
 * carry-in rule: no allowance, no cart pool, no open side.
 */
public final class Supply {

    private final ActionEngine engine;
    private final CharterState state;
    private final FoodTable food;

    public Supply(ActionEngine engine, FoodTable food) {
        this.engine = engine;
        this.state = engine.state();
        this.food = food;
    }

    /**
     * Puts food into a site's store and returns what was accepted: every food item, never a non-food item. The
     * fabric side moves exactly the accepted items.
     */
    public Map<String, Integer> deposit(String carrier, CoreId siteId, Map<String, Integer> offered, List<HistoryEvent> events) {
        Map<String, Integer> accepted = preview(carrier, siteId, offered);
        if (accepted.isEmpty()) return accepted;
        int items = 0;
        double nourishment = 0;
        for (Map.Entry<String, Integer> entry : accepted.entrySet()) {
            items += entry.getValue();
            nourishment += entry.getValue() * food.get(entry.getKey()).nourishment();
        }
        if (items > 0) {
            Store store = state.siege.store(siteId);
            accepted.forEach(store.provisions::add);
            events.add(new SiegeEvents.FoodDeposited(engine.now(), state.siegeClock.now(), siteId, carrier,
                    java.util.Collections.unmodifiableMap(new TreeMap<>(accepted)), nourishment, false));
        }
        return accepted;
    }

    /** What {@link #deposit} would accept, changing nothing. */
    public Map<String, Integer> preview(String carrier, CoreId siteId, Map<String, Integer> offered) {
        Map<String, Integer> accepted = new LinkedHashMap<>();
        Optional<Core> site = state.core(siteId);
        if (site.isEmpty()) return accepted;
        for (Map.Entry<String, Integer> entry : new TreeMap<>(offered).entrySet()) {
            FoodEntry kind = food.get(entry.getKey());
            if (kind == null || entry.getValue() <= 0) continue;
            accepted.put(entry.getKey(), entry.getValue());
        }
        return accepted;
    }
}
