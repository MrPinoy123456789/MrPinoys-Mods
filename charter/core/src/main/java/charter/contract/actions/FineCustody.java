package charter.contract.actions;

import charter.contract.Justice;
import charter.contract.Term;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.List;
import java.util.Optional;

/**
 * A custody fine (D10): at most {@code custodyFineCapMarks} Marks, paid through the payment port (see
 * {@link Justice}). Nothing is paid when no provider is installed. The prisoner is then free.
 */
public record FineCustody(OrgId faction, PlayerId prisoner, long marks) implements Action {

    public static final Reason NO_CURRENCY = new Reason("fines_need_currency", "Fines need a coin currency, and this server has none.");
    public static final Reason NOTHING_TO_FINE = new Reason("nothing_to_fine", "That player has no kingdom with coin to fine.");

    private List<Term> terms() {
        return List.of(new Term.FineMarks(marks));
    }

    @Override
    public OrgId actingFor() {
        return faction;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.JUDGE);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (!ctx.hasProvider()) return Outcome.refuse(NO_CURRENCY);
        if (Justice.finePayer(ctx, faction, prisoner).isEmpty()) return Outcome.refuse(NOTHING_TO_FINE);
        Justice.Plan plan = Justice.plan(ctx, faction, prisoner, terms());
        return plan.ok() ? Outcome.OK : Outcome.refuse(plan.refusal());
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return Justice.impose(ctx, faction, prisoner, Justice.plan(ctx, faction, prisoner, terms())).events();
    }
}
