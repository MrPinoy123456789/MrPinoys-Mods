package charter.gov.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.GovEvents;
import charter.gov.Office;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OfficeId;
import charter.id.OrgId;

import java.util.List;
import java.util.Optional;

/**
 * Removes whoever holds an office. Removing the Lord is reserved to the members'
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
        return ctx.state.office(office).map(o -> o.reserved).orElse(false);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Optional<Office> found = ctx.state.office(office);
        if (found.isEmpty() || !found.get().org.equals(org)) return Outcome.refuse(Appoint.NO_SUCH_OFFICE);
        if (found.get().vacant()) return Outcome.refuse(new Reason("office_vacant", "Nobody holds that office."));
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Office o = ctx.state.office(office).orElseThrow();
        var former = o.holder();
        o.vacate();
        return List.of(new GovEvents.OfficeVacated(ctx.now, o.id, org, former, "removed"));
    }
}
