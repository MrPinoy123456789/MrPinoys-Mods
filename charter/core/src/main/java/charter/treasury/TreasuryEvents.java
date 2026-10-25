package charter.treasury;

import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;

/** History events for treasuries. */
public final class TreasuryEvents {

    private TreasuryEvents() {
    }

    public record Deposited(long at, OrgId org, Currency currency, long amount, PlayerId by) implements HistoryEvent {
    }

    public record Withdrawn(long at, OrgId org, Currency currency, long amount, PlayerId to) implements HistoryEvent {
    }

    public record Transferred(long at, OrgId from, OrgId to, Currency currency, long amount) implements HistoryEvent {
    }
}
