package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.GovEvents;
import charter.gov.Office;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgReasons;
import charter.org.Organization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An operator names the leader of a kingdom: the player joins it if they are not in it yet and takes the Lord's
 * seat, replacing whoever held it. The vote that guards the Lord's seat and a closed door do not apply to an
 * operator. A player who belongs to another kingdom is refused. Operators only.
 */
public record AppointLeader(OrgId org, PlayerId player) implements Action {

    static final Reason NOT_A_KINGDOM = new Reason("not_a_kingdom", "Leaders are named for a kingdom, not for a sub-faction.");
    static final Reason ELSEWHERE = new Reason("leader_elsewhere", "That player belongs to another kingdom. They must leave it first.");

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
        if (!ctx.admin()) return Outcome.refuse(OrgReasons.NOT_YOU);
        Organization o = ctx.state.org(org).orElse(null);
        if (o == null || !o.exists()) return Outcome.refuse(OrgReasons.NOT_YOU);
        if (!o.isTopLevel()) return Outcome.refuse(NOT_A_KINGDOM);
        var member = ctx.state.membership(player);
        if (member.isPresent() && !member.get().org().equals(org)) return Outcome.refuse(ELSEWHERE);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        if (!ctx.state.isMember(player, org)) ctx.state.join(player, org, ctx.now);
        Office lord = ctx.state.office(ctx.state.org(org).orElseThrow().governance.deciders).orElseThrow();
        lord.unseatAll();
        lord.seat(player, ctx.now);
        events.add(new GovEvents.OfficeFilled(ctx.now, lord.id, org, player));
        return events;
    }
}
