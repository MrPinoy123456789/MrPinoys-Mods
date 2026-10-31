package charter.contract.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.treasury.Currency;

import java.util.List;
import java.util.Optional;

/**
 * The old personal safe (D10), closed now that custody fines are paid through the payment port. Kept registered
 * so stored records still read back; it refuses every time, and the stored safes are never read.
 *
 * @deprecated legacy stored data only
 */
@Deprecated
public record DepositPersonal(PlayerId player, Currency currency, long amount) implements Action {

    @Override
    public OrgId actingFor() {
        return null;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        return Outcome.refuse(new charter.engine.Reason("safe_closed", "The personal safe is closed; custody fines are paid from the kingdom's treasury."));
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return List.of();
    }
}
