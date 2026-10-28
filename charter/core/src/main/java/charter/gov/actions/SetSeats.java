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
 * with two or more holders to fewer seats unseats them all and opens an election
 * among those former holders; with fewer holders it unseats the newest.
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
        // A council that shrinks does not keep whoever sat first: every holder steps down and the
        // former holders choose who keeps the seats (a lone holder, or no drop below the holders, is as before).
        boolean council = seats >= 1 && o.holders().size() >= 2 && seats < o.holders().size();
        java.util.Set<charter.id.PlayerId> former = new java.util.LinkedHashSet<>();
        if (council) {
            for (Office.Seat seat : o.holders()) former.add(seat.player());
            for (Office.Seat seat : o.holders()) {
                events.add(new GovEvents.OfficeVacated(ctx.now, o.id, org, seat.player(), "seat removed"));
            }
            o.unseatAll();
        } else {
            for (int i = o.holders().size() - 1; i >= seats && i >= 0; i--) {
                events.add(new GovEvents.OfficeVacated(ctx.now, o.id, org, o.holders().get(i).player(), "seat removed"));
            }
        }
        o.setSeats(seats);
        o.setDeciding(deciding);
        events.add(new GovEvents.SeatsChanged(ctx.now, o.id, org, seats, deciding));
        if (council) ctx.engine.openElection(o.id, null, former);
        return events;
    }
}
