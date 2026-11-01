package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgReasons;
import charter.org.Organization;

import java.util.List;
import java.util.Optional;

/**
 * An operator puts a player into a kingdom without a request or an invite: no one has to be there to admit them, and
 * a closed door does not apply. They take the kingdom's race the way anyone joining does. A player who belongs to
 * another kingdom is refused. Operators only.
 */
public record AddMember(OrgId org, PlayerId player) implements Action {

    static final Reason NOT_A_KINGDOM = new Reason("not_a_kingdom", "Members are added to a kingdom, not to a sub-faction.");
    static final Reason ELSEWHERE = new Reason("member_elsewhere", "That player belongs to another kingdom. They must leave it first.");

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
        if (member.isPresent() && member.get().org().equals(org)) return Outcome.refuse(OrgReasons.ALREADY_MEMBER);
        if (member.isPresent()) return Outcome.refuse(ELSEWHERE);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return Joining.join(ctx, player, org);
    }
}
