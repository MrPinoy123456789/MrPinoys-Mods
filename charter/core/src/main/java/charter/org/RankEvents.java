package charter.org;

import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.GrantId;
import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.List;

/**
 * History events for ranks (charter.org.Ranks). Each names the rank by its id and by the name it had at that moment, so
 * the story reads right after a rename and an audit can undo the change: a rename keeps the old name, a removal keeps
 * who held the rank, a move keeps both places (1 is the top), giving a rank keeps the one held before.
 */
public final class RankEvents {

    private RankEvents() {
    }

    public record RankAdded(long at, OrgId org, String rank, String name) implements HistoryEvent {
    }

    public record RankRenamed(long at, OrgId org, String rank, String from, String to) implements HistoryEvent {
    }

    /** {@code holders} went back to the default rank. Any powers the rank carried were taken back with it. */
    public record RankRemoved(long at, OrgId org, String rank, String name, List<PlayerId> holders) implements HistoryEvent {
    }

    public record RankMoved(long at, OrgId org, String rank, String name, int from, int to) implements HistoryEvent {
    }

    /** {@code from} is the name of the rank the player held before. */
    public record RankGiven(long at, OrgId org, PlayerId player, String rank, String name, String from) implements HistoryEvent {
    }

    /** {@code grant} is the grant to the rank that carries the power. */
    public record RankPowerGiven(long at, OrgId org, String rank, String name, Power power, GrantId grant) implements HistoryEvent {
    }

    public record RankPowerTaken(long at, OrgId org, String rank, String name, Power power) implements HistoryEvent {
    }
}
