package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.gov.Office;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgEvents;
import charter.org.OrgReasons;

import java.util.List;
import java.util.Optional;

/**
 * Removes a member. Expelling whoever holds a reserved office (the Lord) is
 * itself reserved: only the members' vote can do it.
 */
public record Expel(OrgId org, PlayerId player) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.MANAGE_MEMBERS);
    }

    @Override
    public boolean reserved(ActionContext ctx) {
        for (Office office : ctx.state.offices(org)) {
            if (office.reserved && office.holds(player)) return true;
        }
        return false;
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (!ctx.state.isMember(player, org)) return Outcome.refuse(OrgReasons.NOT_A_MEMBER);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.leave(player);
        return List.of(new OrgEvents.Expelled(ctx.now, org, player));
    }
}
