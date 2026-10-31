package charter.treasury.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.treasury.TreasuryEvents;

import java.util.List;
import java.util.Optional;

/**
 * Credits Marks to a treasury through the payment port. Charter never takes coin from a player, so only an operator
 * or the rules (a scenario's funding) may run it; members deposit with the currency mod's own commands.
 */
public record Deposit(OrgId org, long marks) implements Action {

    public static final Reason NOT_BY_PLAYERS = new Reason("deposit_elsewhere", "Deposits are made with the coin currency's treasury commands.");

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (marks <= 0) return Outcome.refuse(new Reason("bad_amount", "Deposit at least one Mark."));
        if (ctx.actor instanceof Actor.Player) return Outcome.refuse(NOT_BY_PLAYERS);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.earn(org, marks, "deposit");
        return List.of(new TreasuryEvents.DepositedMarks(ctx.now, org, marks));
    }
}
