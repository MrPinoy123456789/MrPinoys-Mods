package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.org.OrgEvents;
import charter.org.Organization;

import java.util.List;
import java.util.Optional;

/** Renames an organization. Amending the charter is reserved to the members' vote. */
public record Rename(OrgId org, String name) implements Action {

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
        return Found.checkName(ctx, name);
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Organization o = ctx.state.org(org).orElseThrow();
        String from = o.name();
        o.rename(name.strip());
        return List.of(new OrgEvents.Renamed(ctx.now, org, from, o.name()));
    }
}
