package charter.org;

import charter.gov.Preset;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;

/** History events for organizations and membership. */
public final class OrgEvents {

    private OrgEvents() {
    }

    public record Founded(long at, OrgId org, String name, Preset preset, PlayerId founder) implements HistoryEvent {
    }

    /** A forming top-level organization gained its second member. */
    public record Activated(long at, OrgId org) implements HistoryEvent {
    }

    public record SubOrgCreated(long at, OrgId org, OrgId parent, String name, Preset preset, PlayerId leader) implements HistoryEvent {
    }

    public record Invited(long at, OrgId org, PlayerId player) implements HistoryEvent {
    }

    public record Joined(long at, OrgId org, PlayerId player, OrgId from) implements HistoryEvent {
    }

    /** A player asked to join a faction. */
    public record Asked(long at, OrgId org, PlayerId player) implements HistoryEvent {
    }

    /** A faction took in a player who had asked. The usual Joined follows. */
    public record Admitted(long at, OrgId org, PlayerId player) implements HistoryEvent {
    }

    public record Rejected(long at, OrgId org, PlayerId player) implements HistoryEvent {
    }

    /** A request waited longer than joinRequestDays. */
    public record RequestLapsed(long at, OrgId org, PlayerId player) implements HistoryEvent {
    }

    public record Left(long at, OrgId org, PlayerId player) implements HistoryEvent {
    }

    public record Expelled(long at, OrgId org, PlayerId player) implements HistoryEvent {
    }

    public record Renamed(long at, OrgId org, String from, String to) implements HistoryEvent {
    }
}
