package charter;

import charter.config.Knobs;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.ActionEngine;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.org.Organization;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.state.CharterState;
import charter.time.Clock;
import charter.time.Durations;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A small world for core tests: a state, an engine, default knobs and a manual
 * wall clock. Everything goes through the real engine; nothing is set up by
 * reaching into the state, except marking players as seen.
 */
public final class Fixture {

    public final Knobs knobs = new Knobs();
    public final CharterState state = new CharterState();
    public final Clock.Manual clock = new Clock.Manual(1_800_000_000_000L);
    public final ActionEngine engine = new ActionEngine(state, knobs, clock);

    public static PlayerId player(String name) {
        return new PlayerId(UUID.nameUUIDFromBytes(("player:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    public Outcome as(PlayerId player, Action action) {
        return engine.run(new Actor.Player(player), action);
    }

    public Outcome admin(Action action) {
        return engine.run(new Actor.Admin("test"), action);
    }

    /** Marks players as seen now. */
    public void seen(PlayerId... players) {
        for (PlayerId p : players) state.touch(p, "", clock.now());
    }

    /** Advances wall time, marks the given players seen at the end, and ticks governance. */
    public void pass(long millis, PlayerId... stillSeen) {
        clock.advance(millis);
        seen(stillSeen);
        engine.tick();
    }

    public void passDays(double days, PlayerId... stillSeen) {
        pass(Durations.days(days), stillSeen);
    }

    /** Founds an organization with the founder and the other members joined, all through the engine. */
    public OrgId found(String name, Preset preset, PlayerId founder, PlayerId... others) {
        seen(founder);
        Outcome founded = as(founder, new Found(founder, name, preset));
        if (!founded.done()) throw new IllegalStateException("found failed: " + founded);
        OrgId org = state.orgNamed(name).orElseThrow().id;
        for (PlayerId other : others) {
            seen(other);
            Outcome invited = preset == Preset.LORDSHIP ? as(founder, new InviteMember(org, other)) : admin(new InviteMember(org, other));
            if (!invited.done()) throw new IllegalStateException("invite failed: " + invited);
            Outcome accepted = as(other, new AcceptInvite(other, org));
            if (!accepted.done()) throw new IllegalStateException("accept failed: " + accepted);
        }
        return org;
    }

    public Organization org(OrgId id) {
        return state.org(id).orElseThrow();
    }

    public Office office(OrgId org, String name) {
        return state.officeNamed(org, name).orElseThrow();
    }

    /** The first holder of an office, or null when nobody holds it. */
    public PlayerId holder(OrgId org, String name) {
        Office office = office(org, name);
        return office.vacant() ? null : office.holders().get(0).player();
    }

    /** Proposes, has the listed members vote yes, and returns the vote id. */
    public VoteId motion(PlayerId proposer, Action action, PlayerId... yes) {
        Outcome opened = engine.propose(proposer, action);
        if (!(opened instanceof Outcome.VoteOpened v)) throw new IllegalStateException("propose failed: " + opened);
        for (PlayerId voter : yes) engine.cast(voter, v.vote(), Vote.Ballot.YES);
        return v.vote();
    }

    public Vote vote(VoteId id) {
        return state.vote(id).orElseThrow();
    }

    /** Closes every open vote by running the clock past the vote duration. */
    public void closeVotes(PlayerId... stillSeen) {
        pass(Durations.hours(knobs.voteDurationHours) + 1, stillSeen);
    }

    /** A test-only action that needs a power over an organization and records that it ran. */
    public record Use(OrgId org, Power needed) implements Action {
        public record Used(long at, OrgId org, Power power) implements HistoryEvent {
        }

        @Override public OrgId actingFor() { return org; }
        @Override public Optional<Power> power() { return Optional.of(needed); }
        @Override public Outcome check(ActionContext ctx) { return Outcome.OK; }
        @Override public List<HistoryEvent> apply(ActionContext ctx) { return List.of(new Used(ctx.now, org, needed)); }
    }
}
