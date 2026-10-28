package charter.org.actions;

import charter.engine.ActionContext;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.MemberRecord;
import charter.org.OrgEvents;
import charter.org.OrgStatus;
import charter.org.Organization;

import java.util.ArrayList;
import java.util.List;

/** The steps of taking a player into a faction, shared by accepting an invite and being admitted. */
final class Joining {

    private Joining() {
    }

    static List<HistoryEvent> join(ActionContext ctx, PlayerId player, OrgId org) {
        OrgId from = ctx.state.membership(player).map(MemberRecord::org).orElse(null);
        ctx.state.leave(player);
        ctx.state.join(player, org, ctx.now);
        ctx.state.removeInvite(org, player);
        ctx.state.removeJoinRequestsOf(player);
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new OrgEvents.Joined(ctx.now, org, player, from));
        Organization joined = ctx.state.org(org).orElseThrow();
        if (joined.status() == OrgStatus.FORMING && ctx.state.members(org).size() >= 2) {
            ctx.state.setStatus(org, OrgStatus.ACTIVE);
            events.add(new OrgEvents.Activated(ctx.now, org));
        }
        return events;
    }
}
