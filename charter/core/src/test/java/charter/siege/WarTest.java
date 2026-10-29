package charter.siege;

import charter.engine.Outcome;
import charter.land.Core;
import charter.land.LandReasons;
import charter.land.actions.PlantCamp;
import charter.siege.actions.DeclareWar;
import charter.time.Durations;
import charter.treasury.Currency;

import java.util.HashMap;
import java.util.Map;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.siege.SiegeTest.GIL;
import static charter.siege.SiegeTest.KRIS;
import static charter.siege.SiegeTest.ZED;
import static charter.siege.SiegeTest.ch;

/** War to capture an outpost (protected keeps, O1): declaring, the notice, the hold, the capture, the window and the truce. */
public final class WarTest {

    private WarTest() {
    }

    public static void run() {
        declaringNeedsAnOutpostOfYourOwnAndARaisedTarget();
        captureCannotBeginUntilTheNoticeIsOver();
        holdingTheChunkWithNoDefenderCapturesIt();
        aDefenderInTheChunkResetsTheHold();
        aGoalThatLapsesStartsACooldown();
        capturedOutpostsOnlyGoToTheCaptorsForAWhile();
    }

    /** Presence the test sets by hand. */
    static final class Presence implements WarInputs {
        final Map<charter.id.CoreId, Integer> attackers = new HashMap<>();
        final Map<charter.id.CoreId, Integer> defenders = new HashMap<>();

        public int attackers(WarBook.Goal goal, Core outpost) {
            return attackers.getOrDefault(outpost.id, 0);
        }

        public int defenders(Core outpost) {
            return defenders.getOrDefault(outpost.id, 0);
        }
    }

    /** Ashfall and Brinewatch each with a raised outpost. */
    private static final class Pair {
        final SiegeTest.World w = SiegeTest.world();
        final Presence presence = new Presence();
        Core theirs;

        Pair() {
            w.sim.inputs(presence);
            w.f.as(KRIS, new PlantCamp(w.ashfall, ch(40, 0)));
            w.f.as(ZED, new PlantCamp(w.brine, ch(60, 0)));
            w.raise();
            theirs = w.f.state.coresOf(w.brine).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
        }

        Outcome declare() {
            return w.f.as(KRIS, new DeclareWar(w.ashfall, theirs.id));
        }
    }

    static void declaringNeedsAnOutpostOfYourOwnAndARaisedTarget() {
        SiegeTest.World w = SiegeTest.world();
        w.f.as(ZED, new PlantCamp(w.brine, ch(60, 0)));
        Core theirs = w.f.state.coresOf(w.brine).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
        eq("war: a target still being raised is refused", DeclareWar.NOT_RAISED.key(), w.f.as(KRIS, new DeclareWar(w.ashfall, theirs.id)).reasonKey());
        w.raise();
        eq("war: with no outpost of your own you cannot declare", DeclareWar.NO_OUTPOST_OF_YOURS.key(),
                w.f.as(KRIS, new DeclareWar(w.ashfall, theirs.id)).reasonKey());
        w.f.as(KRIS, new PlantCamp(w.ashfall, ch(40, 0)));
        eq("war: your own outpost still raising does not count", DeclareWar.NO_OUTPOST_OF_YOURS.key(),
                w.f.as(KRIS, new DeclareWar(w.ashfall, theirs.id)).reasonKey());
        w.raise();
        eq("war: a plain member cannot declare", "needs_vote", w.f.as(GIL, new DeclareWar(w.ashfall, theirs.id)).reasonKey());
        eq("war: a keep is no target", DeclareWar.NO_TARGET.key(), w.f.as(KRIS, new DeclareWar(w.ashfall, w.keep.id)).reasonKey());
        Outcome done = w.f.as(KRIS, new DeclareWar(w.ashfall, theirs.id));
        check("war: the Lord declares: " + done, done.done());
        WarBook.Goal goal = w.f.state.wars.goals.getFirst();
        eq("war: the notice is twelve hours", goal.declaredAt + Durations.hours(12), goal.opensAt);
        eq("war: the goal lasts two days", goal.declaredAt + Durations.hours(48), goal.expiresAt);
        eq("war: it is told", 1, w.f.state.history.of(SiegeEvents.WarDeclared.class).size());
        eq("war: the same outpost cannot be named twice", DeclareWar.ALREADY_NAMED.key(), w.f.as(KRIS, new DeclareWar(w.ashfall, theirs.id)).reasonKey());
        Core own = w.f.state.coresOf(w.ashfall).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
        eq("war: your own outpost is no target", DeclareWar.OWN_OUTPOST.key(), w.f.as(KRIS, new DeclareWar(w.ashfall, own.id)).reasonKey());
    }

    static void captureCannotBeginUntilTheNoticeIsOver() {
        Pair p = new Pair();
        check("war: declared", p.declare().done());
        var claim = p.w.f.state.claimAt(p.theirs.center).orElseThrow();
        p.presence.attackers.put(p.theirs.id, 3);
        p.w.advance(Durations.hours(11));
        check("war: before the notice is over the outpost is protected", p.w.rules().protectedClaim(claim));
        eq("war: and nothing has been held", 0L, p.w.f.state.wars.goals.getFirst().progress);
        check("war: no opening yet", !p.w.f.state.history.has(SiegeEvents.WarOpened.class));
        p.w.advance(Durations.hours(1) + Durations.MINUTE);
        check("war: the notice runs out and capture opens", p.w.f.state.history.has(SiegeEvents.WarOpened.class));
        check("war: an outpost named by an open war is not protected", !p.w.rules().protectedClaim(claim));
    }

    static void holdingTheChunkWithNoDefenderCapturesIt() {
        Pair p = new Pair();
        p.declare();
        long deposit = p.theirs.deposit;
        long before = p.w.f.org(p.w.ashfall).ledger.balance(Currency.DIAMOND);
        p.w.advance(Durations.hours(12) + Durations.MINUTE);
        p.presence.attackers.put(p.theirs.id, 2);
        p.w.advance(Durations.minutes(9));
        check("war: nine minutes is not enough", p.w.f.state.core(p.theirs.id).isPresent());
        p.w.advance(Durations.minutes(2));
        check("war: ten minutes captures it", p.w.f.state.core(p.theirs.id).isEmpty());
        eq("war: its deposit goes to the attackers", before + deposit, p.w.f.org(p.w.ashfall).ledger.balance(Currency.DIAMOND));
        check("war: its land is released", p.w.f.state.claimAt(p.theirs.center).isEmpty());
        check("war: the goal is done", p.w.f.state.wars.goals.isEmpty());
        check("war: and told", p.w.f.state.history.has(SiegeEvents.OutpostCaptured.class));
        check("war: the nations cannot fight again at once", p.w.f.state.wars.truce(p.w.ashfall, p.w.brine, p.w.f.state.siegeClock.now()));
    }

    static void aDefenderInTheChunkResetsTheHold() {
        Pair p = new Pair();
        p.declare();
        p.w.advance(Durations.hours(12) + Durations.MINUTE);
        p.presence.attackers.put(p.theirs.id, 2);
        p.w.advance(Durations.minutes(8));
        check("war: eight minutes held", p.w.f.state.wars.goals.getFirst().progress >= Durations.minutes(7));
        p.presence.defenders.put(p.theirs.id, 1);
        p.w.advance(Durations.minutes(1));
        eq("war: a defender enters and the hold starts over", 0L, p.w.f.state.wars.goals.getFirst().progress);
        p.w.advance(Durations.minutes(20));
        check("war: while a defender stands there nothing is taken", p.w.f.state.core(p.theirs.id).isPresent());
    }

    static void aGoalThatLapsesStartsACooldown() {
        Pair p = new Pair();
        p.declare();
        p.w.advance(Durations.hours(48) + Durations.MINUTE);
        check("war: with no one holding it the goal runs out", p.w.f.state.wars.goals.isEmpty() && p.w.f.state.history.has(SiegeEvents.WarLapsed.class));
        check("war: the outpost stands", p.w.f.state.core(p.theirs.id).isPresent());
        eq("war: they cannot declare again at once", DeclareWar.TRUCE.key(), p.declare().reasonKey());
        p.w.advance(Durations.days(3) + Durations.MINUTE);
        check("war: after the cooldown they can", p.declare().done());
    }

    static void capturedOutpostsOnlyGoToTheCaptorsForAWhile() {
        Pair p = new Pair();
        p.declare();
        p.w.advance(Durations.hours(12) + Durations.MINUTE);
        p.presence.attackers.put(p.theirs.id, 2);
        p.w.advance(Durations.minutes(11));
        check("war: captured", p.w.f.state.core(p.theirs.id).isEmpty());
        eq("war: the chunk is held for the captors", java.util.Optional.of(p.w.ashfall),
                p.w.f.state.wars.windowHolder(p.theirs.center, p.w.f.state.siegeClock.now()));
        eq("war: the loser may not plant there in the window", LandReasons.WINDOW_HELD.key(),
                p.w.f.as(ZED, new PlantCamp(p.w.brine, p.theirs.center)).reasonKey());
        p.w.advance(Durations.minutes(31));
        check("war: after the window the ground is plain wilderness again",
                p.w.f.state.wars.windowHolder(p.theirs.center, p.w.f.state.siegeClock.now()).isEmpty());
        check("war: and anyone may plant there", p.w.f.as(ZED, new PlantCamp(p.w.brine, p.theirs.center)).done());
    }
}
