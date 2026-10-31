package charter.treasury.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.treasury.Currency;

import java.util.List;
import java.util.Optional;

/**
 * The old item treasury's transfer, kept registered so a stored record of it still reads back. It does
 * nothing now: the treasury is Marks held by the payment provider.
 *
 * @deprecated legacy stored data only, see {@link Transfer}
 */
@Deprecated
public record LegacyTransfer(OrgId from, OrgId to, Currency currency, long amount) implements Action {

    private static final Reason REFUSED = new Reason("legacy_treasury", "That is the old item treasury, which is closed.");

    @Override
    public OrgId actingFor() {
        return from;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        return Outcome.refuse(REFUSED);
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return List.of();
    }
}
