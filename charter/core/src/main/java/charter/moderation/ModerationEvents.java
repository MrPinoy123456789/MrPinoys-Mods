package charter.moderation;

import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;

/**
 * What a Lord did to one of their own citizens, and what an operator did about a Lord's Ledger access. Each record
 * carries the reason the Lord gave, so an operator can read the history file; the public Chronicle line leaves the
 * reason out.
 */
public final class ModerationEvents {

    private ModerationEvents() {
    }

    public record Muted(long at, OrgId org, PlayerId lord, PlayerId player, long durationMillis, String reason) implements HistoryEvent {
    }

    public record Unmuted(long at, OrgId org, PlayerId lord, PlayerId player) implements HistoryEvent {
    }

    public record Suspended(long at, OrgId org, PlayerId lord, PlayerId player, long durationMillis, String reason) implements HistoryEvent {
    }

    public record Unsuspended(long at, OrgId org, PlayerId lord, PlayerId player) implements HistoryEvent {
    }

    public record Kicked(long at, OrgId org, PlayerId lord, PlayerId player, String reason) implements HistoryEvent {
    }

    /** An operator gave a Lord read only Ledger access for {@code durationMillis}. Recorded under the operator, so it is an admin event. */
    public record LedgerGranted(long at, PlayerId player, long durationMillis) implements HistoryEvent {
    }

    /** Ledger access ended early: an operator took it back, or the player stopped being a Lord. */
    public record LedgerRevoked(long at, PlayerId player, String why) implements HistoryEvent {
    }
}
