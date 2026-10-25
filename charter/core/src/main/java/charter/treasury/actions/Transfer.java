package charter.treasury.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.treasury.Currency;
import charter.treasury.TreasuryEvents;

import java.util.List;
import java.util.Optional;

/** Moves currency from one treasury to another. Needs the spend power over the payer. */
public record Transfer(OrgId from, OrgId to, Currency currency, long amount) implements Action {

    @Override
    public OrgId actingFor() {
        return from;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.SPEND);
    }

    @Override
    public ScopeNeed need(ActionContext ctx) {
        return ScopeNeed.spend(currency, amount);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (amount <= 0) return Outcome.refuse(new Reason("bad_amount", "Transfer at least one."));
        if (from.equals(to) || ctx.state.org(to).isEmpty()) return Outcome.refuse(Reason.NO_SUCH_ORG);
        if (ctx.state.org(from).orElseThrow().ledger.balance(currency) < amount) return Outcome.refuse(Withdraw.SHORT);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.org(from).orElseThrow().ledger.take(currency, amount);
        ctx.state.org(to).orElseThrow().ledger.add(currency, amount);
        return List.of(new TreasuryEvents.Transferred(ctx.now, from, to, currency, amount));
    }
}
