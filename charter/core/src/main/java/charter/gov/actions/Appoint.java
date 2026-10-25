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
import charter.id.PlayerId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Puts a member into an office, replacing whoever held it. Filling a reserved
 * office (the Lord) is reserved to the members' vote.
 */
public record Appoint(OrgId org, OfficeId office, PlayerId player) implements Action {

    public static final Reason NO_SUCH_OFFICE = new Reason("no_such_office", "There is no such office here.");

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
        if (found.isEmpty() || !found.get().org.equals(org)) return Outcome.refuse(NO_SUCH_OFFICE);
        if (!ctx.state.isMember(player, org)) return Outcome.refuse(new Reason("not_a_member", "Only a member can hold an office here."));
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Office o = ctx.state.office(office).orElseThrow();
        List<HistoryEvent> events = new ArrayList<>();
        if (!o.vacant() && !o.holder().equals(player)) {
            events.add(new GovEvents.OfficeVacated(ctx.now, o.id, org, o.holder(), "replaced"));
        }
        o.fill(player, ctx.now);
        events.add(new GovEvents.OfficeFilled(ctx.now, o.id, org, player));
        return events;
    }
}
