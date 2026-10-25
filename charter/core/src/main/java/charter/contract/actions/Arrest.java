package charter.contract.actions;

import charter.contract.ContractEvents;
import charter.contract.ContractReasons;
import charter.contract.Custody;
import charter.contract.Justice;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;

import java.util.List;
import java.util.Optional;

/**
 * An officer takes a player into custody (DESIGN.md §15.5). The fabric side has
 * seen the lead used and reports where the target stands; core checks that the
 * chunk is the arresting faction's own land and records the custody, which lapses
 * after {@code custodyLapseMinutes} if no sentence follows. Needs the arrest power.
 */
public record Arrest(OrgId faction, PlayerId prisoner, ChunkKey where) implements Action {

    @Override
    public OrgId actingFor() {
        return faction;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.ARREST);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (prisoner.equals(ctx.player())) return Outcome.refuse(ContractReasons.SELF);
        Optional<Claim> claim = ctx.state.claimAt(where);
        if (claim.isEmpty() || !ctx.state.root(claim.get().owner).equals(ctx.state.root(faction))) {
            return Outcome.refuse(ContractReasons.NOT_YOUR_LAND);
        }
        boolean inCustody = ctx.state.contracts.custodyOf(prisoner)
                .map(c -> Justice.custodyLive(c, ctx.knobs, ctx.now)).orElse(false);
        if (inCustody || Justice.holding(ctx.state, ctx.knobs, prisoner, ctx.now).isPresent()) {
            return Outcome.refuse(ContractReasons.ALREADY_HELD);
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Claim claim = ctx.state.claimAt(where).orElseThrow();
        ctx.state.contracts.holdInCustody(new Custody(prisoner, faction, ctx.player(), claim.core(), ctx.now));
        return List.of(new ContractEvents.Arrested(ctx.now, faction, prisoner, ctx.player()));
    }
}
