package charter.gov;

import charter.history.HistoryEvent;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;

/** History events for votes and offices. */
public final class GovEvents {

    private GovEvents() {
    }

    public record VoteOpened(long at, VoteId vote, OrgId org, Vote.Kind kind, String about, PlayerId proposer, long closesAt) implements HistoryEvent {
    }

    public record VotePassed(long at, VoteId vote, OrgId org, long yes, long no, long abstain) implements HistoryEvent {
    }

    public record VoteFailed(long at, VoteId vote, OrgId org, String why) implements HistoryEvent {
    }

    /** A vote passed but its action was refused when it ran, because things changed while it was open. */
    public record VoteRefusedAtExecution(long at, VoteId vote, OrgId org, String reasonKey) implements HistoryEvent {
    }

    public record OfficeCreated(long at, OfficeId office, OrgId org, String name, boolean reserved) implements HistoryEvent {
    }

    public record OfficeFilled(long at, OfficeId office, OrgId org, PlayerId holder) implements HistoryEvent {
    }

    public record SeatsChanged(long at, OfficeId office, OrgId org, int seats, Office.Deciding deciding) implements HistoryEvent {
    }

    public record OfficeVacated(long at, OfficeId office, OrgId org, PlayerId formerHolder, String why) implements HistoryEvent {
    }
}
