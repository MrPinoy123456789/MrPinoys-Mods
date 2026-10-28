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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Changes how many seats an office has and how its holders decide. Always
 * reserved: only a vote of the members runs it. This is how a faction changes its
 * form: 0 seats is a Commune, 1 a Lordship, more a council. Lowering the seats
 * unseats the most recently seated holders.
 */
public record SetSeats(OrgId org, OfficeId office, int seats, Office.Deciding deciding) implements Action {

    public static final Reason BAD_SEATS = new Reason("bad_seats", "That is not a number of seats this faction may have.");
    public static final Reason NO_CHANGE = new Reason("no_change", "That is how it already is.");

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.AMEND);
    }

    @Override
    public boolean reserved(ActionContext ctx) {
        return true;
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Optional<Office> found = ctx.state.office(office);
        if (found.isEmpty() || !found.get().org.equals(org)) return Outcome.refuse(Appoint.NO_SUCH_OFFICE);
        if (seats < 0 || seats > ctx.knobs.maxSeats || deciding == null) return Outcome.refuse(BAD_SEATS);
        if (found.get().seats() == seats && found.get().deciding() == deciding) return Outcome.refuse(NO_CHANGE);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Office o = ctx.state.office(office).orElseThrow();
        List<HistoryEvent> events = new ArrayList<>();
        for (int i = o.holders().size() - 1; i >= seats && i >= 0; i--) {
            events.add(new GovEvents.OfficeVacated(ctx.now, o.id, org, o.holders().get(i).player(), "seat removed"));
        }
        o.setSeats(seats);
        o.setDeciding(deciding);
        events.add(new GovEvents.SeatsChanged(ctx.now, o.id, org, seats, deciding));
        return events;
    }
}
