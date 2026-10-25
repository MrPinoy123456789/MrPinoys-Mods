package charter.contract.actions;

import charter.contract.ContractEvents;
import charter.contract.Justice;
import charter.contract.Term;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An execution is banishment, not a death ban (DESIGN.md §15.5): a short respawn
 * delay, then banishment from the faction's land. The prisoner keeps their
 * inventory (D18). The fabric side kills the player and runs the wait; core
 * records the contract and answers {@link Justice#respawnDelayLeft}.
 */
public record Execute(OrgId faction, PlayerId prisoner, long respawnDelayMillis, long banishmentMillis) implements Action {

    private List<Term> terms(ActionContext ctx) {
        return List.of(new Term.RespawnDelay(respawnDelayMillis),
                new Term.Banishment(ctx.state.root(faction), banishmentMillis));
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
        Justice.Plan plan = Justice.plan(ctx, faction, prisoner, terms(ctx));
        return plan.ok() ? Outcome.OK : Outcome.refuse(plan.refusal());
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Justice.Plan plan = Justice.plan(ctx, faction, prisoner, terms(ctx));
        Justice.Imposition done = Justice.impose(ctx, faction, prisoner, plan);
        long delay = 0;
        long banish = 0;
        for (Term t : plan.terms()) {
            if (t instanceof Term.RespawnDelay r) delay = r.durationMillis();
            if (t instanceof Term.Banishment b) banish = b.durationMillis();
        }
        List<HistoryEvent> events = new ArrayList<>(done.events());
        events.add(new ContractEvents.Executed(ctx.now, done.contract().id, faction, prisoner, delay, banish));
        return events;
    }
}
