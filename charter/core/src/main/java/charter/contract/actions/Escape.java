package charter.contract.actions;

import charter.contract.Contract;
import charter.contract.ContractEvents;
import charter.contract.ContractReasons;
import charter.contract.Justice;
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
 * A prisoner leaves their cell or tether by their own effort (PLAN.md §7.4). The
 * fabric side reports it when the prisoner is outside the cell and still in the
 * keep's chunks. It is resistance: the sentence ends, the weekly total counts
 * the time served, and nothing is marked against the prisoner (DESIGN.md §15.4).
 * Needs no power; only the prisoner (or the rules, for the fabric driver) may run it.
 */
public record Escape(PlayerId prisoner) implements Action {

    @Override
    public OrgId actingFor() {
        return null;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (ctx.player() != null && !ctx.player().equals(prisoner)) return Outcome.refuse(ContractReasons.NOT_YOU);
        if (Justice.holding(ctx.state, ctx.knobs, prisoner, ctx.now).isEmpty()) return Outcome.refuse(ContractReasons.NOT_HELD);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Contract contract = Justice.holding(ctx.state, ctx.knobs, prisoner, ctx.now).orElseThrow();
        contract.resist(ctx.now, "escaped");
        ctx.state.contracts.punishments.served(contract.id, ctx.now - contract.createdAt);
        return List.of(new ContractEvents.Escaped(ctx.now, contract.id, prisoner, contract.imposer.org()));
    }
}
