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
import charter.treasury.TreasuryEvents;

import java.util.List;
import java.util.Optional;

/**
 * Pays coin out of the treasury to a player at the vault. Needs the spend power with a limit covering the amount, or
 * a vote. This action only decides and records: the fabric side then hands the coin over through the provider's own
 * treasury give route, which takes the Marks out of the treasury itself, so nothing is charged here. With no
 * provider there is no coin to hand over and the action is refused.
 */
public record Withdraw(OrgId org, long marks, PlayerId to) implements Action {

    public static final Reason SHORT = new Reason("treasury_short", "The treasury does not hold that much.");
    public static final Reason NO_PROVIDER = new Reason("no_currency", "There is no coin currency installed, so there is no treasury.");

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
        return ScopeNeed.spend(marks);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (marks <= 0) return Outcome.refuse(new Reason("bad_amount", "Withdraw an amount above nothing."));
        if (!ctx.hasProvider()) return Outcome.refuse(NO_PROVIDER);
        if (!ctx.canPay(org, marks)) return Outcome.refuse(SHORT);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return List.of(new TreasuryEvents.WithdrawnMarks(ctx.now, org, marks, to));
    }
}
