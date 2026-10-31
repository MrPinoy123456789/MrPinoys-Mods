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
import charter.id.PlayerId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Puts a member into a seat of an office; a full one seat office replaces its holder. Filling a reserved
 * office (the Lord) is reserved to the members' vote.
 */
public record Appoint(OrgId org, OfficeId office, PlayerId player) implements Action {

    public static final Reason NO_SUCH_OFFICE = new Reason("no_such_office", "There is no such office here.");

    public static final Reason ALREADY_SEATED = new Reason("already_seated", "That player already holds this office.");
    public static final Reason LOCKED = new Reason("appointing_locked", "Offices are filled by the server for now.");
    public static final Reason NO_SEATS = new Reason("no_seats", "This office has no seats.");
    public static final Reason OFFICE_FULL = new Reason("office_full", "Every seat is taken. Remove someone first.");

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
        // An election the members hold still seats its winner; people with power may not seat anyone.
        if (ctx.actor instanceof charter.engine.Actor.Player && !ctx.knobs.leadersAppointOffices) return Outcome.refuse(LOCKED);
        Optional<Office> found = ctx.state.office(office);
        if (found.isEmpty() || !found.get().org.equals(org)) return Outcome.refuse(NO_SUCH_OFFICE);
        if (Deciders.isDecisionOffice(ctx.state, found.get())) {
            // Only the deciders seat anyone, or the election of an empty seat (the members choose a Lord, never replace one).
            Outcome gate = Deciders.gate(ctx, found.get(), true);
            if (gate.refused()) return gate;
        }
        if (!ctx.state.isMember(player, org)) return Outcome.refuse(new Reason("not_a_member", "Only a member can hold an office here."));
        Office o = found.get();
        if (o.holds(player)) return Outcome.refuse(ALREADY_SEATED);
        if (o.seats() == 0) return Outcome.refuse(NO_SEATS);
        if (o.full() && o.seats() > 1) return Outcome.refuse(OFFICE_FULL);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Office o = ctx.state.office(office).orElseThrow();
        List<HistoryEvent> events = new ArrayList<>();
        if (o.full()) {
            PlayerId replaced = o.holders().get(0).player();
            o.unseat(replaced);
            events.add(new GovEvents.OfficeVacated(ctx.now, o.id, org, replaced, "replaced"));
        }
        o.seat(player, ctx.now);
        events.add(new GovEvents.OfficeFilled(ctx.now, o.id, org, player));
        return events;
    }
}
