package charter.org;

import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.List;

/**
 * A break away in progress. A {@code SUBFACTION} that voted to secede and owns no keep has until
 * {@code deadline} to place a Nexus at its own cost; a {@code GROUP} leaving a faction with no
 * sub-factions gathers there until its starter places a named Nexus. Either lapses with no change.
 */
public record Breakaway(Kind kind, OrgId org, PlayerId starter, List<PlayerId> members, long startedAt, long deadline) {

    public enum Kind { SUBFACTION, GROUP }

    public Breakaway {
        members = List.copyOf(members);
    }

    public Breakaway with(PlayerId joiner) {
        List<PlayerId> more = new java.util.ArrayList<>(members);
        if (!more.contains(joiner)) more.add(joiner);
        return new Breakaway(kind, org, starter, more, startedAt, deadline);
    }
}
