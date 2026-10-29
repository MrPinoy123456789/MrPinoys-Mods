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

    /** A camp's record. */
    public static final class CampSite {
        public CampSupply supply = CampSupply.IDLE;
    }

    /** No camps of {@code attacker} count against {@code keep} until the siege clock passes {@code untilSiege}. */
    public record Truce(CoreId keep, OrgId attacker, long untilSiege) {
    }

    /** The food table in use. Configuration, not saved state: the sim sets it, and the fabric side on load. */
    public charter.food.FoodTable food = new charter.food.FoodTable();

    public final Map<CoreId, Store> stores = new LinkedHashMap<>();
    public final Map<CoreId, KeepSite> keeps = new LinkedHashMap<>();
    public final Map<CoreId, CampSite> camps = new LinkedHashMap<>();
    public final List<Allowance> allowances = new ArrayList<>();
    public final List<Truce> truces = new ArrayList<>();

    public Store store(CoreId core) {
        return stores.computeIfAbsent(core, c -> new Store());
    }

    public KeepSite keep(CoreId core) {
        return keeps.computeIfAbsent(core, c -> new KeepSite());
    }

    public CampSite camp(CoreId core) {
        return camps.computeIfAbsent(core, c -> new CampSite());
    }

    public boolean truce(CoreId keep, OrgId attacker, long siegeNow) {
        return truces.stream().anyMatch(t -> t.keep().equals(keep) && t.attacker().equals(attacker) && siegeNow < t.untilSiege());
    }
}
