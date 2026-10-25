package charter.engine;

import charter.config.Knobs;
import charter.gov.GovEvents;
import charter.gov.Office;
import charter.gov.Vote;
import charter.gov.VoteMethod;
import charter.gov.actions.Appoint;
import charter.grant.Authority;
import charter.grant.Power;
import charter.grant.Who;
import charter.history.HistoryEvent;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.org.MemberRecord;
import charter.org.Organization;
import charter.state.CharterState;
import charter.time.Clock;
import charter.time.Durations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every consequential change goes through here (PLAN.md §3): authority, then
 * governance, then the action's own checks, then the change, then the record.
 * Called on the server thread only, so actions never interleave.
 *
 * <p>A player without authority is refused with {@link Reason#NEEDS_VOTE} when a
 * vote could do it; proposing that vote is a separate, explicit step
 * ({@link #propose}). A passed vote runs its action again through
 * {@link #run} as {@link Actor.Vote}, so nothing stale is ever applied.
 */
public final class ActionEngine {

    private final CharterState state;
    private final Knobs knobs;
    private final Clock clock;

    public ActionEngine(CharterState state, Knobs knobs, Clock clock) {
        this.state = state;
        this.knobs = knobs;
        this.clock = clock;
    }

    public CharterState state() {
        return state;
    }

    public Knobs knobs() {
        return knobs;
    }

    public long now() {
        return clock.now();
    }

    // ---- running actions ----

    public Outcome run(Actor actor, Action action) {
        long now = clock.now();
        ActionContext ctx = new ActionContext(state, knobs, now, actor, this);
        OrgId org = action.actingFor();
        if (org != null && state.org(org).isEmpty()) return Outcome.refuse(Reason.NO_SUCH_ORG);

        Authority authority = authorize(ctx, action);
        if (!authority.ok()) return Outcome.refuse(refusalFor(ctx, action));
        ctx.setAuthority(authority);

        Outcome checked = action.check(ctx);
        if (checked.refused()) return checked;

        List<HistoryEvent> events = action.apply(ctx);
        record(actor, events);
        return new Outcome.Done(List.copyOf(events));
    }

    private Authority authorize(ActionContext ctx, Action action) {
        if (ctx.actor instanceof Actor.Admin || ctx.actor instanceof Actor.System) return Authority.ADMIN;
        Optional<Power> power = action.power();
        OrgId org = action.actingFor();
        if (power.isEmpty() || org == null) return Authority.NOT_NEEDED;

        Who who;
        if (ctx.actor instanceof Actor.Player p) {
            who = new Who.AsPlayer(p.id());
        } else if (ctx.actor instanceof Actor.Vote v) {
            Optional<Vote> vote = state.vote(v.id());
            if (vote.isEmpty() || vote.get().state() != Vote.State.PASSED) return Authority.NONE;
            who = new Who.AsOrg(vote.get().org);
        } else {
            return Authority.NONE;
        }

        if (action.reserved(ctx)) {
            return who instanceof Who.AsOrg(OrgId voter) && voter.equals(org) ? Authority.SOURCE : Authority.NONE;
        }
        return ctx.resolver().resolve(who, org, power.get(), action.need(ctx), action.needsRedelegable());
    }

    private Reason refusalFor(ActionContext ctx, Action action) {
        PlayerId player = ctx.player();
        boolean voteCould = player != null && action.actingFor() != null && state.isMember(player, action.actingFor());
        if (action.reserved(ctx)) return voteCould ? Reason.RESERVED.with(Reason.RESERVED.text() + " Propose it as a vote.") : Reason.RESERVED;
        return voteCould ? Reason.NEEDS_VOTE : Reason.NO_AUTHORITY;
    }

    private void record(Actor actor, List<HistoryEvent> events) {
        boolean admin = actor instanceof Actor.Admin;
        for (HistoryEvent event : events) {
            state.history.record(event, actor.describe(), admin);
        }
    }

    /** Records events the rules produce on their own (timers, the siege), outside any action. */
    public void recordSystem(List<HistoryEvent> events) {
        record(new Actor.System(), events);
    }

    // ---- votes ----

    /** Members who may vote in this organization now: direct members of long enough standing. */
    public Set<PlayerId> eligibleVoters(OrgId org, long now) {
        long minJoined = now - Durations.days(knobs.voteEligibilityDays);
        Set<PlayerId> out = new LinkedHashSet<>();
        for (MemberRecord member : state.members(org)) {
            if (member.joinedAt() <= minJoined) out.add(member.player());
        }
        return out;
    }

    public boolean active(PlayerId player, long now) {
        return state.knownPlayer(player)
                .map(p -> p.lastSeen >= now - Durations.days(knobs.activeWindowDays))
                .orElse(false);
    }

    /** Opens a vote of {@code action.actingFor()} on this action, proposed by a member who may vote. */
    public Outcome propose(PlayerId proposer, Action action) {
        long now = clock.now();
        OrgId org = action.actingFor();
        if (org == null || state.org(org).isEmpty()) return Outcome.refuse(Reason.NO_SUCH_ORG);
        Set<PlayerId> electorate = eligibleVoters(org, now);
        if (!electorate.contains(proposer)) return Outcome.refuse(Reason.NOT_ELIGIBLE);

        ActionContext ctx = new ActionContext(state, knobs, now, new Actor.Player(proposer), this);
        ctx.setAuthority(Authority.SOURCE);
        Outcome checked = action.check(ctx);
        if (checked.refused()) return checked;

        Organization o = state.org(org).orElseThrow();
        Power power = action.power().orElse(null);
        Vote vote = openVote(org, Vote.Kind.MOTION, action, null, proposer, power, o.governance.method(power),
                o.governance.quorumShare, electorate, now, action.getClass().getSimpleName());
        vote.cast(proposer, Vote.Ballot.YES);
        settleIfComplete(vote, now);
        return new Outcome.VoteOpened(vote.id);
    }

    /** Opens an election to fill an office. Used for succession; any eligible member may also call it. */
    public Outcome openElection(OfficeId officeId, PlayerId proposer) {
        long now = clock.now();
        Optional<Office> office = state.office(officeId);
        if (office.isEmpty()) return Outcome.refuse(Reason.NO_SUCH_ORG);
        OrgId org = office.get().org;
        Set<PlayerId> electorate = eligibleVoters(org, now);
        if (proposer != null && !electorate.contains(proposer)) return Outcome.refuse(Reason.NOT_ELIGIBLE);
        boolean alreadyOpen = state.votes().stream()
                .anyMatch(v -> v.open() && v.kind == Vote.Kind.ELECTION && officeId.equals(v.office));
        if (alreadyOpen) return Outcome.refuse(new Reason("election_open", "An election for that office is already open."));
        Organization o = state.org(org).orElseThrow();
        Vote vote = openVote(org, Vote.Kind.ELECTION, null, officeId, proposer, null, VoteMethod.MAJORITY,
                o.governance.quorumShare, electorate, now, "election of " + office.get().name);
        return new Outcome.VoteOpened(vote.id);
    }

    private Vote openVote(OrgId org, Vote.Kind kind, Action motion, OfficeId office, PlayerId proposer, Power power,
                          VoteMethod method, double quorumShare, Set<PlayerId> electorate, long now, String about) {
        Set<PlayerId> active = new LinkedHashSet<>();
        for (PlayerId voter : electorate) {
            if (active(voter, now)) active.add(voter);
        }
        long closes = now + Durations.hours(knobs.voteDurationHours);
        Vote vote = new Vote(state.ids.vote(), org, kind, motion, office, proposer, power, method, quorumShare,
                electorate, active, now, closes);
        state.addVote(vote);
        record(proposer == null ? new Actor.System() : new Actor.Player(proposer),
                List.of(new GovEvents.VoteOpened(now, vote.id, org, kind, about, proposer, closes)));
        return vote;
    }

    /** Casts or changes a ballot. Only the electorate fixed at opening may vote. */
    public Outcome cast(PlayerId voter, VoteId voteId, Vote.Ballot ballot) {
        long now = clock.now();
        Optional<Vote> found = state.vote(voteId);
        if (found.isEmpty()) return Outcome.refuse(new Reason("no_such_vote", "That vote does not exist."));
        Vote vote = found.get();
        if (!vote.open() || now >= vote.closesAt) return Outcome.refuse(new Reason("vote_closed", "That vote has closed."));
        if (!vote.electorate.contains(voter)) return Outcome.refuse(Reason.NOT_ELIGIBLE);
        boolean electionBallot = ballot.choice() == Vote.Choice.CANDIDATE;
        if ((vote.kind == Vote.Kind.ELECTION) != electionBallot && ballot.choice() != Vote.Choice.ABSTAIN) {
            return Outcome.refuse(new Reason("wrong_ballot", vote.kind == Vote.Kind.ELECTION
                    ? "This is an election: name a candidate." : "This is a motion: vote yes, no or abstain."));
        }
        if (electionBallot && !vote.electorate.contains(ballot.candidate()) && !state.isMember(ballot.candidate(), vote.org)) {
            return Outcome.refuse(new Reason("not_a_candidate", "Only a member can be elected."));
        }
        vote.cast(voter, ballot);
        settleIfComplete(vote, now);
        return Outcome.OK;
    }

    private void settleIfComplete(Vote vote, long now) {
        if (vote.open() && vote.everyoneVoted()) settle(vote, now);
    }

    // ---- the governance tick ----

    /**
     * Closes votes whose time is up and runs the ones that passed, and empties
     * offices whose holder has been absent too long, opening a succession
     * election. Wall time only. Call regularly (the fabric side does so each sim step).
     */
    public void tick() {
        long now = clock.now();
        for (Vote vote : new ArrayList<>(state.votes())) {
            if (vote.open() && now >= vote.closesAt) settle(vote, now);
        }
        long absentLimit = now - Durations.days(knobs.absentLordDays);
        for (Office office : new ArrayList<>(state.allOffices())) {
            if (!office.vacateWhenAbsent || office.vacant() || state.org(office.org).isEmpty()) continue;
            long lastSeen = state.knownPlayer(office.holder()).map(p -> p.lastSeen).orElse(0L);
            if (lastSeen < absentLimit) {
                PlayerId former = office.holder();
                office.vacate();
                record(new Actor.System(), List.of(new GovEvents.OfficeVacated(now, office.id, office.org, former, "absent")));
                openElection(office.id, null);
            }
        }
    }

    /**
     * Closes an open vote now, counting the ballots cast so far, as if its time had
     * run out (admin "vote close"). Refuses a vote that is not open.
     */
    public Outcome closeVote(VoteId voteId) {
        Optional<Vote> found = state.vote(voteId);
        if (found.isEmpty()) return Outcome.refuse(new Reason("no_such_vote", "That vote does not exist."));
        if (!found.get().open()) return Outcome.refuse(new Reason("vote_closed", "That vote has closed."));
        settle(found.get(), clock.now());
        return Outcome.OK;
    }

    private void settle(Vote vote, long now) {
        long cast = vote.ballots().size();
        if (cast < vote.quorumNeeded()) {
            vote.settle(Vote.State.FAILED, "no quorum");
            record(new Actor.System(), List.of(new GovEvents.VoteFailed(now, vote.id, vote.org, "no quorum")));
            return;
        }
        if (vote.kind == Vote.Kind.ELECTION) {
            settleElection(vote, now);
            return;
        }
        int yes = (int) vote.count(Vote.Choice.YES);
        int no = (int) vote.count(Vote.Choice.NO);
        if (!vote.method.passes(yes, no)) {
            String why = yes == no ? "tie" : "not carried";
            vote.settle(Vote.State.FAILED, why);
            record(new Actor.System(), List.of(new GovEvents.VoteFailed(now, vote.id, vote.org, why)));
            return;
        }
        vote.settle(Vote.State.PASSED, "carried");
        record(new Actor.System(), List.of(new GovEvents.VotePassed(now, vote.id, vote.org, yes, no, vote.count(Vote.Choice.ABSTAIN))));
        execute(vote, vote.motion, now);
    }

    private void settleElection(Vote vote, long now) {
        Map<PlayerId, Integer> tally = new LinkedHashMap<>();
        for (Vote.Ballot ballot : vote.ballots().values()) {
            if (ballot.choice() == Vote.Choice.CANDIDATE) tally.merge(ballot.candidate(), 1, Integer::sum);
        }
        PlayerId winner = null;
        int best = 0;
        boolean tie = false;
        for (Map.Entry<PlayerId, Integer> entry : tally.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                winner = entry.getKey();
                tie = false;
            } else if (entry.getValue() == best) {
                tie = true;
            }
        }
        if (winner == null || tie) {
            String why = winner == null ? "no candidate" : "tie";
            vote.settle(Vote.State.FAILED, why);
            record(new Actor.System(), List.of(new GovEvents.VoteFailed(now, vote.id, vote.org, why)));
            return;
        }
        vote.settle(Vote.State.PASSED, "elected");
        record(new Actor.System(), List.of(new GovEvents.VotePassed(now, vote.id, vote.org, best, 0, vote.count(Vote.Choice.ABSTAIN))));
        execute(vote, new Appoint(vote.org, vote.office, winner), now);
    }

    private void execute(Vote vote, Action action, long now) {
        Outcome outcome = run(new Actor.Vote(vote.id), action);
        if (outcome instanceof Outcome.Refused refused) {
            vote.settle(Vote.State.REFUSED_AT_EXECUTION, refused.reason().key());
            record(new Actor.System(), List.of(new GovEvents.VoteRefusedAtExecution(now, vote.id, vote.org, refused.reason().key())));
        } else {
            vote.settle(Vote.State.EXECUTED, "done");
        }
    }
}
