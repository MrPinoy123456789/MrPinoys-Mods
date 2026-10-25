package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.MemberRecord;
import charter.org.OrgEvents;
import charter.org.OrgReasons;

import java.util.List;
import java.util.Optional;

/** A player leaves their organization, giving up any office they held in it. */
public record Leave(PlayerId player) implements Action {

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
        if (!ctx.admin() && !player.equals(ctx.player())) return Outcome.refuse(OrgReasons.NOT_YOU);
        if (ctx.state.membership(player).isEmpty()) return Outcome.refuse(OrgReasons.NOT_A_MEMBER);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        OrgId org = ctx.state.membership(player).map(MemberRecord::org).orElseThrow();
        ctx.state.leave(player);
        return List.of(new OrgEvents.Left(ctx.now, org, player));
    }
}
