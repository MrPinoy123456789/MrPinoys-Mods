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
import charter.org.OrgStatus;
import charter.org.Organization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A player accepts an invitation. A player can move between organizations in the
 * same tree (leaving any office in the old one); joining another tree means
 * leaving the current one first, since a player belongs to one tree in v0.
 */
public record AcceptInvite(PlayerId player, OrgId org) implements Action {

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
        if (ctx.state.org(org).isEmpty()) return Outcome.refuse(charter.engine.Reason.NO_SUCH_ORG);
        if (ctx.state.invite(org, player).isEmpty()) return Outcome.refuse(OrgReasons.NO_INVITE);
        if (Joining.closed(ctx, org)) return Outcome.refuse(OrgReasons.CLOSED);
        Optional<MemberRecord> current = ctx.state.membership(player);
        if (current.isPresent()) {
            if (current.get().org().equals(org)) return Outcome.refuse(OrgReasons.ALREADY_MEMBER);
            if (!ctx.state.root(current.get().org()).equals(ctx.state.root(org))) return Outcome.refuse(OrgReasons.OTHER_TREE);
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return Joining.join(ctx, player, org);
    }
}
