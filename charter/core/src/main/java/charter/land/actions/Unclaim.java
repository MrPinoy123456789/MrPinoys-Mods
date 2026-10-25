package charter.land.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.LandGraph;
import charter.land.LandReasons;

import java.util.List;
import java.util.Optional;

/** Gives up a claim. The chunks of a keep or the banner chunk of a camp cannot be given up alone. */
public record Unclaim(OrgId org, ChunkKey chunk) implements Action {

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
        Optional<Claim> claim = ctx.state.claimAt(chunk);
        if (claim.isEmpty() || !claim.get().owner.equals(org)) return Outcome.refuse(LandReasons.NOT_YOUR_CLAIM);
        if (claim.get().fixed()) return Outcome.refuse(LandReasons.PART_OF_CORE);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return new LandGraph(ctx.state, ctx.knobs).unclaim(org, chunk, ctx.now);
    }
}
