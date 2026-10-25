package charter.contract;

import charter.config.Knobs;
import charter.grant.Power;
import charter.id.ClaimId;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.treasury.Currency;

import java.util.Set;

/**
 * The term vocabulary shared by every contract, signed or imposed (DESIGN.md
 * §15.4). Each term is something the mod can enforce. Adding a new kind of power
 * over others means adding a term here and a line in {@link BasisCaps}; the caps,
 * notoriety and Chronicle come with it.
 *
 * <p>Durations are wall milliseconds, except an {@link Access} term inside an
 * occupation, which runs on the siege clock with its contract.
 */
public sealed interface Term {

    /** Wall or siege milliseconds this term lasts, or 0 if it happens once. */
    default long durationMillis() {
        return 0;
    }

    /** The same term with a different duration, or this term if it has none. */
    default Term withDuration(long millis) {
        return this;
    }

    /** True if the term counts toward a player's weekly punishment total (DESIGN.md §15.5). */
    default boolean countsTowardWeeklyCap() {
        return false;
    }

    /** How harsh the term is, for notoriety; each term scales it by its own size. */
    double severity(Knobs knobs);

    /** Scheduled transfers: a payment or tribute between treasuries. */
    record Payment(Currency currency, long amount) implements Term {
        public double severity(Knobs knobs) { return 1; }
    }

    /** A fine from a player's own deposit. */
    record Fine(long diamonds) implements Term {
        public double severity(Knobs knobs) { return knobs.custodyFineCapDiamonds <= 0 ? 0 : (double) diamonds / knobs.custodyFineCapDiamonds; }
    }

    /** Items into a named chest by a deadline. */
    record Delivery(String itemId, int count, long deadlineMillis) implements Term {
        public double severity(Knobs knobs) { return 1; }
    }

    /** Authority added through the action engine. */
    record GrantTerm(Power power, OrgId subject, long durationMillis) implements Term {
        public double severity(Knobs knobs) { return 2; }
        public Term withDuration(long millis) { return new GrantTerm(power, subject, millis); }
    }

    /** Who may build or use what in a keep's claims; an occupation's control of the granary rides on it. */
    record Access(CoreId keep, long durationMillis) implements Term {
        public double severity(Knobs knobs) { return 2; }
        public Term withDuration(long millis) { return new Access(keep, millis); }
    }

    /** The player is held in a named cell of a keep. */
    record Confinement(long durationMillis, CoreId keep, String cell) implements Term {
        public boolean countsTowardWeeklyCap() { return true; }
        public double severity(Knobs knobs) { return 2.0 * durationMillis / charter.time.Durations.DAY; }
        public Term withDuration(long millis) { return new Confinement(millis, keep, cell); }
    }

    /** The player is held to named land instead of a cell; the bond and, later, the indenture. */
    record Tether(long durationMillis, CoreId keep) implements Term {
        public boolean countsTowardWeeklyCap() { return true; }
        public double severity(Knobs knobs) { return 2.0 * durationMillis / charter.time.Durations.DAY; }
        public Term withDuration(long millis) { return new Tether(millis, keep); }
    }

    /** The player is pushed out of a faction's land on entry. Does not count toward the weekly cap. */
    record Banishment(OrgId from, long durationMillis) implements Term {
        public double severity(Knobs knobs) { return 0.5 * durationMillis / charter.time.Durations.DAY; }
        public Term withDuration(long millis) { return new Banishment(from, millis); }
    }

    /** The player waits before respawning. */
    record RespawnDelay(long durationMillis) implements Term {
        public boolean countsTowardWeeklyCap() { return true; }
        public double severity(Knobs knobs) { return (double) durationMillis / charter.time.Durations.HOUR; }
        public Term withDuration(long millis) { return new RespawnDelay(millis); }
    }

    /** A faction cannot raise camps against the named factions. */
    record RelationLock(Set<OrgId> against, long durationMillis) implements Term {
        public RelationLock { against = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(against)); }
        public double severity(Knobs knobs) { return 1; }
        public Term withDuration(long millis) { return new RelationLock(against, millis); }
    }

    /** A lower cap on a faction's claims. */
    record ClaimLimit(int max, long durationMillis) implements Term {
        public double severity(Knobs knobs) { return 1; }
        public Term withDuration(long millis) { return new ClaimLimit(max, millis); }
    }

    /** Named claims change owner. */
    record Cede(Set<ClaimId> claims) implements Term {
        public Cede { claims = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(claims)); }
        public double severity(Knobs knobs) { return 1.0 + claims.size(); }
    }

    /** Prisoners freed, vassals released, occupations ended. */
    record Release(String what) implements Term {
        public double severity(Knobs knobs) { return 0; }
    }
}
