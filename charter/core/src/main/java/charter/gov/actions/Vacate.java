package charter.gov.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Deciders;
import charter.gov.GovEvents;
import charter.gov.Office;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OfficeId;
import charter.id.OrgId;

import java.util.List;
import java.util.Optional;

/**
 * Removes everyone who holds an office. Removing the Lord is reserved to the members'
 * vote; the moment it runs, every grant that reached the Lord through the office
 * stops working, because grants follow the office, not the person.
 */
public record Vacate(OrgId org, OfficeId office) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.APPOINT);
    }

    @Override
    public boolean reserved(ActionContext ctx) {
        return ctx.state.office(office).map(o -> o.reserved && !Deciders.isDecisionOffice(ctx.state, o)).orElse(false);
    }

    @Override
    public boolean needsSeatMajority(ActionContext ctx) {
        return ctx.state.office(office).map(o -> Deciders.isDecisionOffice(ctx.state, o)).orElse(false);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Optional<Office> found = ctx.state.office(office);
        if (found.isEmpty() || !found.get().org.equals(org)) return Outcome.refuse(Appoint.NO_SUCH_OFFICE);
        if (Deciders.isDecisionOffice(ctx.state, found.get())) {
            Outcome gate = Deciders.gate(ctx, found.get(), false);
            if (gate.refused()) return gate;
        }
        if (found.get().vacant()) return Outcome.refuse(new Reason("office_vacant", "Nobody holds that office."));
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Office o = ctx.state.office(office).orElseThrow();
        List<HistoryEvent> events = new java.util.ArrayList<>();
        for (Office.Seat seat : o.holders()) {
            events.add(new GovEvents.OfficeVacated(ctx.now, o.id, org, seat.player(), "removed"));
        }
        o.unseatAll();
        return events;
    }
}
