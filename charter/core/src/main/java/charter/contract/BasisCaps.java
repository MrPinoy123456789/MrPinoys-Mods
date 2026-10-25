package charter.contract;

import charter.config.Knobs;
import charter.engine.Reason;
import charter.time.Durations;

/**
 * What each basis allows, and how far (DESIGN.md §15.4, §15.5, §24). The caps are
 * the same for everyone and are enforced by cutting a term down to its cap; a term
 * the basis does not allow, or one too small to mean anything, is refused. The
 * weekly total across factions is not here: it needs the ledger
 * ({@link PunishmentLedger}).
 */
public final class BasisCaps {

    private BasisCaps() {
    }

    /** A term after the caps: the term to use, whether it was cut, or why it is refused. */
    public record Checked(Term term, boolean cut, Reason refusal) {
        public boolean ok() {
            return refusal == null;
        }

        static Checked ok(Term term, boolean cut) {
            return new Checked(term, cut, null);
        }

        static Checked refuse(Reason reason) {
            return new Checked(null, false, reason);
        }
    }

    /** True if the basis can carry this kind of term at all. */
    public static boolean allows(Basis basis, Term term) {
        return switch (basis) {
            case CUSTODY -> term instanceof Term.Confinement || term instanceof Term.Tether
                    || term instanceof Term.Banishment || term instanceof Term.RespawnDelay || term instanceof Term.Fine;
            case WAR_GOAL -> term instanceof Term.Payment || term instanceof Term.Delivery || term instanceof Term.GrantTerm
                    || term instanceof Term.Cede || term instanceof Term.RelationLock || term instanceof Term.ClaimLimit
                    || term instanceof Term.Release;
            case OCCUPATION -> term instanceof Term.Access || term instanceof Term.Payment || term instanceof Term.Release;
        };
    }

    public static Checked check(Basis basis, Term term, Knobs knobs) {
        if (!allows(basis, term)) return Checked.refuse(ContractReasons.NOT_ALLOWED);
        return switch (term) {
            case Term.Confinement c -> held(term, c.durationMillis(), knobs);
            case Term.Tether t -> knobs.labourTerms ? held(term, t.durationMillis(), knobs) : Checked.refuse(ContractReasons.BOND_OFF);
            case Term.Banishment b -> limited(term, b.durationMillis(), Durations.hours(knobs.banishmentMaxHours));
            case Term.RespawnDelay r -> limited(term, r.durationMillis(), Durations.minutes(knobs.executionRespawnDelayMaxMinutes));
            case Term.Fine f -> {
                if (f.diamonds() <= 0) yield Checked.refuse(ContractReasons.BAD_TERM);
                long cap = knobs.custodyFineCapDiamonds;
                yield f.diamonds() > cap ? Checked.ok(new Term.Fine(cap), true) : Checked.ok(term, false);
            }
            case Term.Access a -> limited(term, a.durationMillis(), Durations.days(knobs.occupationCapDays));
            case Term.GrantTerm g -> limited(term, g.durationMillis(), Durations.days(knobs.imposedVassalageCapDays));
            case Term.Payment p -> p.amount() <= 0 ? Checked.refuse(ContractReasons.BAD_TERM) : Checked.ok(term, false);
            case Term.Delivery d -> d.count() <= 0 ? Checked.refuse(ContractReasons.BAD_TERM) : Checked.ok(term, false);
            case Term.Cede c -> c.claims().isEmpty() ? Checked.refuse(ContractReasons.BAD_TERM) : Checked.ok(term, false);
            case Term.RelationLock l -> l.against().isEmpty() ? Checked.refuse(ContractReasons.BAD_TERM) : Checked.ok(term, false);
            case Term.ClaimLimit c -> c.max() < 0 ? Checked.refuse(ContractReasons.BAD_TERM) : Checked.ok(term, false);
            case Term.Release r -> Checked.ok(term, false);
        };
    }

    /** Confinement and bonds: at least the minimum, cut to the maximum. */
    private static Checked held(Term term, long duration, Knobs knobs) {
        long min = Durations.minutes(knobs.confinementMinMinutes);
        long max = Durations.hours(knobs.confinementMaxHours);
        if (duration < min) return Checked.refuse(ContractReasons.TOO_SHORT);
        return duration > max ? Checked.ok(term.withDuration(max), true) : Checked.ok(term, false);
    }

    private static Checked limited(Term term, long duration, long max) {
        if (duration <= 0) return Checked.refuse(ContractReasons.BAD_TERM);
        return duration > max ? Checked.ok(term.withDuration(max), true) : Checked.ok(term, false);
    }
}
