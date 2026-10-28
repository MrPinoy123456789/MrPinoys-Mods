package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.gov.Deciders;
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
 * Removes a member. Expelling a holder of the decision makers office is for its
 * deciders only ({@link Deciders}); members cannot vote a ruler out.
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
            if (office.reserved && office.holds(player) && !Deciders.isDecisionOffice(ctx.state, office)) return true;
        }
        return false;
    }

    /** The decision office, when the player being expelled sits on it. */
    private Optional<Office> deciderSeat(ActionContext ctx) {
        return Deciders.of(ctx.state, org).filter(d -> d.holds(player));
    }

    @Override
    public boolean needsSeatMajority(ActionContext ctx) {
        return deciderSeat(ctx).isPresent();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (!ctx.state.isMember(player, org)) return Outcome.refuse(OrgReasons.NOT_A_MEMBER);
        // A ruler is removed only by the deciders; members cannot vote one out (they can break away).
        Optional<Office> seat = deciderSeat(ctx);
        if (seat.isPresent()) return Deciders.gate(ctx, seat.get(), false);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.leave(player);
        return List.of(new OrgEvents.Expelled(ctx.now, org, player));
    }
}
