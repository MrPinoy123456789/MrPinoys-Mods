package charter.land.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.land.LandGraph;

import java.util.List;
import java.util.Optional;

/** Claims one chunk beside the faction's existing land (DESIGN.md §9.1). */
public record ClaimLand(OrgId org, ChunkKey chunk) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.CLAIM);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        return new LandGraph(ctx.state, ctx.knobs).claimProblem(org, chunk, ctx.now)
                .map(Outcome::refuse).orElse(Outcome.OK);
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return new LandGraph(ctx.state, ctx.knobs).claim(org, chunk, ctx.now);
    }
}
