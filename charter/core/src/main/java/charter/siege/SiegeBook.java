package charter.siege;

import charter.id.ClaimId;
import charter.id.CoreId;
import charter.id.OrgId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every piece of siege state, kept in one block of {@code CharterState} and
 * saved with it. Keyed by core and claim ids from the land graph; an entry for a
 * core that no longer exists is dropped on the next step.
 */
public final class SiegeBook {

    /** A keep's siege record. */
    public static final class KeepSite {
        public KeepSupply supply = KeepSupply.SUPPLIED;
        public boolean starving;
        /** Factions whose camps counted against this keep on the last step. */
        public Set<OrgId> besiegers = new LinkedHashSet<>();
    }

    /** A camp's siege record. */
    public static final class CampSite {
        public CampSupply supply = CampSupply.IDLE;
        /** Siege milliseconds of sally protection left (DESIGN.md §10.6). */
        public long sallyLeft = -1;
        /** True once the sally hold ran out; protection returns only when it refills. */
        public boolean exposed;
        /** Factions this camp besieges or raids, as of the last step. */
        public Set<OrgId> targets = new LinkedHashSet<>();
    }

    /** A keep that fell and waits for the besieger's choice. */
    public record PendingFall(CoreId keep, OrgId besieger, long decideBySiege) {
    }

    /** A keep held by another faction without owning it (DESIGN.md §10.8). */
    public record Occupation(CoreId keep, OrgId occupier, long untilSiege) {
    }

    /** No camps of {@code attacker} count against {@code keep} until the siege clock passes {@code untilSiege}. */
    public record Truce(CoreId keep, OrgId attacker, long untilSiege) {
    }

    /** The food table in use. Configuration, not saved state: the sim sets it, and the fabric side on load. */
    public charter.food.FoodTable food = new charter.food.FoodTable();

    public final Map<CoreId, Store> stores = new LinkedHashMap<>();
    public final Map<CoreId, KeepSite> keeps = new LinkedHashMap<>();
    public final Map<CoreId, CampSite> camps = new LinkedHashMap<>();
    /** Siege milliseconds of hold left per outlying claim. Missing means full. */
    public final Map<ClaimId, Long> holds = new LinkedHashMap<>();
    public final List<Allowance> allowances = new ArrayList<>();
    public final List<Truce> truces = new ArrayList<>();
    public final Map<CoreId, PendingFall> falls = new LinkedHashMap<>();
    public final Map<CoreId, Occupation> occupations = new LinkedHashMap<>();
    public final Map<OrgId, Double> notoriety = new LinkedHashMap<>();
    /** Factions that gave up their new-faction protection by raising a camp against someone. */
    public final Set<OrgId> protectionWaived = new HashSet<>();

    public Store store(CoreId core) {
        return stores.computeIfAbsent(core, c -> new Store());
    }

    public KeepSite keep(CoreId core) {
        return keeps.computeIfAbsent(core, c -> new KeepSite());
    }

    public CampSite camp(CoreId core) {
        return camps.computeIfAbsent(core, c -> new CampSite());
    }

    public double notorietyOf(OrgId faction) {
        return notoriety.getOrDefault(faction, 0.0);
    }

    public boolean truce(CoreId keep, OrgId attacker, long siegeNow) {
        return truces.stream().anyMatch(t -> t.keep().equals(keep) && t.attacker().equals(attacker) && siegeNow < t.untilSiege());
    }
}
