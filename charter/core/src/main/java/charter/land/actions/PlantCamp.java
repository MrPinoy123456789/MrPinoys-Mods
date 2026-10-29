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

    public static final charter.engine.Reason NPC_NO_CAMPS = new charter.engine.Reason("npc_no_camps", "The raiders do not plant camps.");

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
        // The raiders never plant camps in this version (NPC strongholds, N6).
        if (ctx.state.org(org).map(o -> o.npc).orElse(false)) return Outcome.refuse(NPC_NO_CAMPS);
        var problem = new LandGraph(ctx.state, ctx.knobs).campProblem(center);
        if (problem.isPresent()) return Outcome.refuse(problem.get());
        var holder = ctx.state.wars.windowHolder(center, ctx.state.siegeClock.now());
        if (holder.isPresent() && !holder.get().equals(ctx.state.root(org))) return Outcome.refuse(LandReasons.WINDOW_HELD);
        if (ctx.state.coresOf(org).stream().anyMatch(c -> !c.isKeep())) return Outcome.refuse(LandReasons.ONE_OUTPOST);
        var organization = ctx.state.org(org).orElseThrow();
        if (organization.parent() != null && ctx.state.members(org).size() < ctx.knobs.outpostMinMembers) {
            return Outcome.refuse(LandReasons.OUTPOST_TOO_SMALL);
        }
        long price = deposit(ctx.state, ctx.knobs, org);
        if (organization.ledger.balance(Currency.DIAMOND) < price) {
            return Outcome.refuse(LandReasons.NO_FUNDS.with("An outpost needs a deposit of " + price + " diamonds and the treasury holds fewer."));
        }
        return Outcome.OK;
    }

    /**
     * The deposit this organization pays now: the Nth outpost a nation holds in all, itself and every sub-faction,
     * costs {@code outpostDeposit} plus {@code outpostDepositStep} for each outpost already held.
     */
    public static long deposit(charter.state.CharterState state, charter.config.Knobs knobs, OrgId org) {
        OrgId root = state.root(org);
        long held = state.cores().stream().filter(c -> !c.isKeep() && state.root(c.owner).equals(root)).count();
        return knobs.outpostDeposit + knobs.outpostDepositStep * held;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        long price = deposit(ctx.state, ctx.knobs, org);
        ctx.state.org(org).orElseThrow().ledger.take(Currency.DIAMOND, price);
        List<HistoryEvent> events = new LandGraph(ctx.state, ctx.knobs).plantCamp(org, center, ctx.now);
        ctx.state.coresOf(org).stream().filter(c -> !c.isKeep() && c.center.equals(center)).findFirst().ifPresent(c -> c.deposit = price);
        return events;
    }
}
