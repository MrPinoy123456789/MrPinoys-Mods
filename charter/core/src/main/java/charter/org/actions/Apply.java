package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.JoinRequest;
import charter.org.OrgEvents;
import charter.org.OrgReasons;
import charter.org.Organization;

import java.util.List;
import java.util.Optional;

/** A player with no faction asks to join one. It waits for the faction's answer. A player's own act. */
public record Apply(PlayerId player, OrgId org) implements Action {

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
        Optional<Organization> found = ctx.state.org(org);
        if (found.isEmpty()) return Outcome.refuse(Reason.NO_SUCH_ORG);
        if (!found.get().isTopLevel() || !found.get().exists() || found.get().npc) return Outcome.refuse(OrgReasons.NOT_JOINABLE);
        if (ctx.state.membership(player).isPresent()) return Outcome.refuse(OrgReasons.ALREADY_MEMBER);
        if (ctx.state.joinRequestOf(player).isPresent()) return Outcome.refuse(OrgReasons.ALREADY_ASKING);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.addJoinRequest(new JoinRequest(org, player, ctx.now));
        return List.of(new OrgEvents.Asked(ctx.now, org, player));
    }
}
