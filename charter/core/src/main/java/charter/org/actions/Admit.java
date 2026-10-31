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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The faction takes in a player who asked to join. */
public record Admit(OrgId org, PlayerId player) implements Action {

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
        if (Joining.closed(ctx, org)) return Outcome.refuse(OrgReasons.CLOSED);
        if (ctx.state.membership(player).isPresent()) return Outcome.refuse(OrgReasons.ALREADY_MEMBER);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new OrgEvents.Admitted(ctx.now, org, player));
        events.addAll(Joining.join(ctx, player, org));
        return events;
    }
}
