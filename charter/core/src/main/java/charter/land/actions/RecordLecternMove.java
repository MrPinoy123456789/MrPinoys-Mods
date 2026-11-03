package charter.land.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.LandEvents;
import charter.land.LandReasons;

import java.util.List;
import java.util.Optional;

/**
 * Records that an operator moved a keep's lectern terminal, for the Chronicle. It changes nothing in the state: the caller
 * has already moved the block and its record.
 */
public record RecordLecternMove(OrgId org, CoreId core) implements Action {

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
        if (!ctx.admin()) return Outcome.refuse(LandReasons.OPERATOR_ONLY);
        if (ctx.state.org(org).isEmpty()) return Outcome.refuse(charter.engine.Reason.NO_SUCH_ORG);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return List.of(new LandEvents.LecternMoved(ctx.now, org, core));
    }
}
