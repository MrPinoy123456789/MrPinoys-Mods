package charter.treasury;

import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;

/**
 * History events for treasuries. The first three records are the old item treasury's and only exist so a stored
 * history still reads back; the Marks records are what Charter writes now.
 */
public final class TreasuryEvents {

    private TreasuryEvents() {
    }

    public record Deposited(long at, OrgId org, Currency currency, long amount, PlayerId by) implements HistoryEvent {
    }

    public record Withdrawn(long at, OrgId org, Currency currency, long amount, PlayerId to) implements HistoryEvent {
    }

    public record Transferred(long at, OrgId from, OrgId to, Currency currency, long amount) implements HistoryEvent {
    }

    public record DepositedMarks(long at, OrgId org, long marks) implements HistoryEvent {
    }

    public record WithdrawnMarks(long at, OrgId org, long marks, PlayerId to) implements HistoryEvent {
    }

    public record TransferredMarks(long at, OrgId from, OrgId to, long marks) implements HistoryEvent {
    }
}
