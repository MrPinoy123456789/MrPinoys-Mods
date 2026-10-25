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

/**
 * Plants the faction's war banner, raising a camp (DESIGN.md §9.3). Beside foreign
 * land is fine, inside it is not. The camp starts RAISING; the siege simulation
 * finishes the raising.
 */
public record PlantCamp(OrgId org, ChunkKey center) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.RAISE_CAMP);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        var problem = new LandGraph(ctx.state, ctx.knobs).campProblem(center);
        if (problem.isPresent()) return Outcome.refuse(problem.get());
        if (ctx.state.org(org).orElseThrow().ledger.balance(Currency.COBBLESTONE) < ctx.knobs.warBannerPriceCobblestone) {
            return Outcome.refuse(LandReasons.NO_FUNDS.with("A war banner costs " + ctx.knobs.warBannerPriceCobblestone + " cobblestone and the treasury holds fewer."));
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.org(org).orElseThrow().ledger.take(Currency.COBBLESTONE, ctx.knobs.warBannerPriceCobblestone);
        return new LandGraph(ctx.state, ctx.knobs).plantCamp(org, center, ctx.now);
    }
}
