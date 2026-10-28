package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgEvents;
import charter.org.OrgReasons;

import java.util.List;
import java.util.Optional;

/** The faction turns away a player who asked to join. */
public record Reject(OrgId org, PlayerId player) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.MANAGE_MEMBERS);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (ctx.state.joinRequest(org, player).isEmpty()) return Outcome.refuse(OrgReasons.NO_REQUEST);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.removeJoinRequest(org, player);
        return List.of(new OrgEvents.Rejected(ctx.now, org, player));
    }
}
