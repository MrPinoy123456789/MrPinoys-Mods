package charter.treasury.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.treasury.Currency;
import charter.treasury.TreasuryEvents;

import java.util.List;
import java.util.Optional;

/**
 * Credits items deposited at the vault. Anyone may give; the fabric side takes
 * the real items from the player before running this.
 */
public record Deposit(OrgId org, Currency currency, long amount) implements Action {

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
        if (amount <= 0) return Outcome.refuse(new Reason("bad_amount", "Deposit at least one."));
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.org(org).orElseThrow().ledger.add(currency, amount);
        return List.of(new TreasuryEvents.Deposited(ctx.now, org, currency, amount, ctx.player()));
    }
}
