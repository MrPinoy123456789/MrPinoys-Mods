package charter.land.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.land.LandGraph;
import charter.land.LandReasons;
import charter.treasury.Currency;

import java.util.List;
import java.util.Optional;

/** Places a nexus: claims a 3x3 keep around the chunk and charges the treasury (DESIGN.md §9.2). */
public record PlaceNexus(OrgId org, ChunkKey center) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.PLACE_CORE);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        // A sub-faction that voted to break away places its Nexus as a faction of its own would: outside every keep's protection.
        LandGraph land = new LandGraph(ctx.state, ctx.knobs);
        var problem = ctx.state.pendingSecession(org).isPresent() ? land.keepProblemForNew(center) : land.keepProblem(org, center);
        if (problem.isPresent()) return Outcome.refuse(problem.get());
        if (ctx.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) < ctx.knobs.keepPriceDiamonds) {
            return Outcome.refuse(LandReasons.NO_FUNDS.with("A keep costs " + ctx.knobs.keepPriceDiamonds + " diamonds and the treasury holds fewer."));
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.org(org).orElseThrow().ledger.take(Currency.DIAMOND, ctx.knobs.keepPriceDiamonds);
        return new LandGraph(ctx.state, ctx.knobs).placeKeep(org, center, ctx.now);
    }
}
