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
 * An operator removes one Lord of a kingdom. They stay a member. With two Lords the other keeps their seat and the
 * office goes back to one seat, so they rule alone; removing the last Lord leaves the seat empty for an operator to
 * fill. Operators only.
 */
public record RemoveLord(OrgId org, PlayerId player) implements Action {

    static final Reason NOT_LORD = new Reason("not_lord", "That player is not a Lord of this kingdom.");

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
        if (!o.isTopLevel()) return Outcome.refuse(AppointLeader.NOT_A_KINGDOM);
        Office lord = o.governance.deciders == null ? null : ctx.state.office(o.governance.deciders).orElse(null);
        if (lord == null || !lord.holds(player)) return Outcome.refuse(NOT_LORD);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        Office lord = ctx.state.office(ctx.state.org(org).orElseThrow().governance.deciders).orElseThrow();
        lord.unseat(player);
        events.add(new GovEvents.OfficeVacated(ctx.now, lord.id, org, player, "removed by an operator"));
        if (lord.seats() > 1 && lord.holders().size() <= 1) {
            lord.setSeats(1);
            lord.setDeciding(Office.Deciding.ALONE);
            events.add(new GovEvents.SeatsChanged(ctx.now, lord.id, org, 1, Office.Deciding.ALONE));
        }
        return events;
    }
}
