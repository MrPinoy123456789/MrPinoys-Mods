package charter.contract.actions;

import charter.contract.Justice;
import charter.contract.Term;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.List;
import java.util.Optional;

/**
 * Imposes an any-terms sentence on a prisoner in custody (DESIGN.md §15.5): the
 * custody basis allows confinement, a bond (if the server allows it), banishment,
 * a respawn delay and a capped fine. Terms are cut to their caps and to what is
 * left of the player's week; a term the basis does not allow is refused. Needs the
 * judge power. {@link Confine}, {@link Execute} and {@link FineCustody} are the
 * common cases.
 */
public record Sentence(OrgId faction, PlayerId prisoner, List<Term> terms) implements Action {

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
        Justice.Plan plan = Justice.plan(ctx, faction, prisoner, terms);
        return plan.ok() ? Outcome.OK : Outcome.refuse(plan.refusal());
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return Justice.impose(ctx, faction, prisoner, Justice.plan(ctx, faction, prisoner, terms)).events();
    }
}
