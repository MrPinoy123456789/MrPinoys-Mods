package charter.grant;

import charter.history.HistoryEvent;
import charter.id.GrantId;
import charter.id.OrgId;

/** History events for grants. */
public final class GrantEvents {

    private GrantEvents() {
    }

    public record Granted(long at, GrantId grant, OrgId subject, Power power, Holder recipient, GrantId parent, boolean redelegable) implements HistoryEvent {
    }

    public record Revoked(long at, GrantId grant, OrgId subject, Power power) implements HistoryEvent {
    }
}
