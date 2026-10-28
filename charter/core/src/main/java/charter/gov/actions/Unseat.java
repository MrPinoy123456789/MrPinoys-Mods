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

import java.util.List;
import java.util.Optional;

/** Removes one holder from an office, leaving the others seated. Reserved when the office is. */
public record Unseat(OrgId org, OfficeId office, PlayerId player) implements Action {

    public static final Reason NOT_SEATED = new Reason("not_seated", "That player does not hold this office.");

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
        if (!found.get().holds(player)) return Outcome.refuse(NOT_SEATED);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Office o = ctx.state.office(office).orElseThrow();
        o.unseat(player);
        return List.of(new GovEvents.OfficeVacated(ctx.now, o.id, org, player, "removed"));
    }
}
