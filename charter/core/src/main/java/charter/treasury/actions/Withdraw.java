package charter.treasury.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.treasury.Currency;
import charter.treasury.TreasuryEvents;

import java.util.List;
import java.util.Optional;

/**
 * Pays items out of the treasury to a player at the vault. Needs the spend power
 * with a limit covering the amount, or a vote; the fabric side hands over the
 * real items after this succeeds.
 */
public record Withdraw(OrgId org, Currency currency, long amount, PlayerId to) implements Action {

    public static final Reason SHORT = new Reason("treasury_short", "The treasury does not hold that much.");

    @Override
    public OrgId actingFor() {
        return org;
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
        if (amount <= 0) return Outcome.refuse(new Reason("bad_amount", "Withdraw at least one."));
        if (ctx.state.org(org).orElseThrow().ledger.balance(currency) < amount) return Outcome.refuse(SHORT);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        ctx.state.org(org).orElseThrow().ledger.take(currency, amount);
        return List.of(new TreasuryEvents.Withdrawn(ctx.now, org, currency, amount, to));
    }
}
