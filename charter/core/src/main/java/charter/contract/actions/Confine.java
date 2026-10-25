package charter.contract.actions;

import charter.contract.Justice;
import charter.contract.Term;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.List;
import java.util.Optional;

/** Sentences a prisoner to a cell of one of the faction's keeps. The prisoner is a mouth on its granary. */
public record Confine(OrgId faction, PlayerId prisoner, long durationMillis, CoreId keep, String cell) implements Action {

    private List<Term> terms() {
        return List.of(new Term.Confinement(durationMillis, keep, cell));
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
        Justice.Plan plan = Justice.plan(ctx, faction, prisoner, terms());
        return plan.ok() ? Outcome.OK : Outcome.refuse(plan.refusal());
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return Justice.impose(ctx, faction, prisoner, Justice.plan(ctx, faction, prisoner, terms())).events();
    }
}
