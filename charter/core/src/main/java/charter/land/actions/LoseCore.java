package charter.land.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.LandGraph;
import charter.land.LandReasons;

import java.util.List;
import java.util.Optional;

/**
 * A keep falls or a camp is destroyed. Nobody chooses this: the siege simulation
 * runs it as {@link Actor.System}, and an operator may for a test scenario. Its
 * claims are reassigned, contested or dropped (DESIGN.md §11).
 */
public record LoseCore(CoreId core) implements Action {

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
        if (!ctx.admin() && !(ctx.actor instanceof Actor.System)) return Outcome.refuse(LandReasons.OPERATOR_ONLY);
        if (ctx.state.core(core).isEmpty()) return Outcome.refuse(LandReasons.NO_SUCH_CORE);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return new LandGraph(ctx.state, ctx.knobs).loseCore(core, ctx.now);
    }
}
