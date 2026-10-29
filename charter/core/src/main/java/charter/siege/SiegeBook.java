package charter.siege;

import charter.id.CoreId;
import charter.id.OrgId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every piece of siege state, kept in one block of {@code CharterState} and
 * saved with it. Keyed by core and claim ids from the land graph; an entry for a
 * core that no longer exists is dropped on the next step.
 */
public final class SiegeBook {

    /** A keep's upkeep record (protected keeps, K2): the smallest warning already given, in days left. Saved. */
    public static final class KeepSite {
        /** 0 until the first warning; then the threshold (in days left) last announced. A refill above a threshold re-arms it. */
        public int warned;
        /** Siege time before which an empty granary cannot lapse the keep: one upkeep period from its first pass (0 = not set yet). */
        public long graceUntilSiege;
    }

    /** The food table in use. Configuration, not saved state: the sim sets it, and the fabric side on load. */
    public charter.food.FoodTable food = new charter.food.FoodTable();

    public final Map<CoreId, Store> stores = new LinkedHashMap<>();
    public final Map<CoreId, KeepSite> keeps = new LinkedHashMap<>();

    public Store store(CoreId core) {
        return stores.computeIfAbsent(core, c -> new Store());
    }

    public KeepSite keep(CoreId core) {
        return keeps.computeIfAbsent(core, c -> new KeepSite());
    }

}
