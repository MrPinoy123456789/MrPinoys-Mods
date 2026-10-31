package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.Invite;
import charter.org.OrgEvents;
import charter.org.OrgReasons;

import java.util.List;
import java.util.Optional;

/** Invites a player to join. They join only by accepting it themselves. */
public record InviteMember(OrgId org, PlayerId player) implements Action {

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
        if (ctx.state.org(org).map(o -> o.npc).orElse(false)) return Outcome.refuse(OrgReasons.NOT_JOINABLE);
        if (Joining.closed(ctx, org)) return Outcome.refuse(OrgReasons.CLOSED);
        if (ctx.state.isMember(player, org)) return Outcome.refuse(new Reason("already_here", "That player is already a member."));
        if (Joining.raceBlocks(ctx, org, player)) return Outcome.refuse(OrgReasons.RACE_MISMATCH);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.addInvite(new Invite(org, player, ctx.now));
        return List.of(new OrgEvents.Invited(ctx.now, org, player));
    }
}
