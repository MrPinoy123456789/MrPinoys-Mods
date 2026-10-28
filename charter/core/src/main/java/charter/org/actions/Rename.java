package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.gov.Deciders;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.org.OrgEvents;
import charter.org.Organization;

import java.util.List;
import java.util.Optional;

/** Renames an organization: a vote of the members in a Commune, else the deciders of its decision office. */
public record Rename(OrgId org, String name) implements Action {

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
        return Deciders.membersDecide(ctx.state, org);
    }

    @Override
    public boolean needsSeatMajority(ActionContext ctx) {
        return !Deciders.membersDecide(ctx.state, org);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Outcome named = Found.checkName(ctx, name);
        if (named.refused()) return named;
        // The name is the deciders' to change: the members of a Lordship or a council cannot vote it.
        return Deciders.of(ctx.state, org).map(d -> Deciders.gate(ctx, d, false)).orElse(Outcome.OK);
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Organization o = ctx.state.org(org).orElseThrow();
        String from = o.name();
        o.rename(name.strip());
        return List.of(new OrgEvents.Renamed(ctx.now, org, from, o.name()));
    }
}
