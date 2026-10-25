package charter.gov;

import charter.engine.Action;
import charter.grant.Power;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A vote of an organization's members (DESIGN.md §6.3).
 *
 * <p>The electorate is fixed when the vote opens: members admitted later cannot
 * vote, and expelling a voter does not remove them or their ballot. Quorum is
 * counted against the electorate members who were active when it opened. A
 * motion carries the action it would authorize; that action is re-checked when
 * the vote passes, since things may have changed while it was open.
 *
 * <p>An election fills an office; each ballot names a candidate and the most
 * named wins. A tie for first fails.
 */
public final class Vote {

    public enum Kind { MOTION, ELECTION }

    public enum State { OPEN, PASSED, FAILED, EXECUTED, REFUSED_AT_EXECUTION }

    public enum Choice { YES, NO, ABSTAIN, CANDIDATE }

    public record Ballot(Choice choice, PlayerId candidate) {
        public static final Ballot YES = new Ballot(Choice.YES, null);
        public static final Ballot NO = new Ballot(Choice.NO, null);
        public static final Ballot ABSTAIN = new Ballot(Choice.ABSTAIN, null);

        public static Ballot candidate(PlayerId who) {
            return new Ballot(Choice.CANDIDATE, who);
        }
    }

    public final VoteId id;
    public final OrgId org;
    public final Kind kind;
    /** The action a motion would authorize; null for an election. */
    public final Action motion;
    /** The office an election fills; null for a motion. */
    public final OfficeId office;
    public final PlayerId proposer;
    public final Power power;
    public final VoteMethod method;
    public final double quorumShare;
    public final Set<PlayerId> electorate;
    public final Set<PlayerId> activeAtOpen;
    public final long openedAt;
    public final long closesAt;

    private final Map<PlayerId, Ballot> ballots = new LinkedHashMap<>();
    private State state = State.OPEN;
    private String result = "";

    public Vote(VoteId id, OrgId org, Kind kind, Action motion, OfficeId office, PlayerId proposer, Power power,
                VoteMethod method, double quorumShare, Set<PlayerId> electorate, Set<PlayerId> activeAtOpen,
                long openedAt, long closesAt) {
        this.id = id;
        this.org = org;
        this.kind = kind;
        this.motion = motion;
        this.office = office;
        this.proposer = proposer;
        this.power = power;
        this.method = method;
        this.quorumShare = quorumShare;
        this.electorate = Set.copyOf(electorate);
        this.activeAtOpen = Set.copyOf(activeAtOpen);
        this.openedAt = openedAt;
        this.closesAt = closesAt;
    }

    public State state() { return state; }
    public String result() { return result; }
    public Map<PlayerId, Ballot> ballots() { return Map.copyOf(ballots); }
    public boolean open() { return state == State.OPEN; }

    /** Engine only: records or replaces a ballot. */
    public void cast(PlayerId voter, Ballot ballot) {
        ballots.put(voter, ballot);
    }

    /** Engine only: moves the vote to its result. */
    public void settle(State newState, String why) {
        state = newState;
        result = why;
    }

    /** Ballots needed for quorum: the quorum share of members active at opening, at least one. */
    public int quorumNeeded() {
        return Math.max(1, (int) Math.ceil(quorumShare * activeAtOpen.size()));
    }

    public boolean everyoneVoted() {
        return ballots.keySet().containsAll(electorate);
    }

    public long count(Choice choice) {
        return ballots.values().stream().filter(b -> b.choice() == choice).count();
    }
}
