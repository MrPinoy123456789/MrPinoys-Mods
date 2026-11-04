package charter.chronicle;

import charter.history.HistoryEvent;
import charter.id.OrgId;

/**
 * Happenings the rules do not record on their own but the Chronicle wants told
 * (docs/2026-11-04-chronicle-design.md 3.4). {@link Milestones} derives them from
 * the state after an action and records them as system events, so a kingdom that
 * grew by an operator's hand is still congratulated in public.
 */
public final class MilestoneEvents {

    private MilestoneEvents() {
    }

    /** A kingdom reached {@code count} members: 5, 10 or 20. Once per threshold per kingdom. */
    public record MembersReached(long at, OrgId org, int count) implements HistoryEvent {
    }

    /**
     * A kingdom's first of something: {@code what} is a fixed key, one of
     * {@link Milestones#FIRST_WAR}, {@link Milestones#FIRST_OUTPOST_TAKEN} or
     * {@link Milestones#FIRST_OUTPOST_HELD}. Once per key per kingdom.
     */
    public record FirstOf(long at, OrgId org, String what) implements HistoryEvent {
    }
}
