package charter.mc;

import charter.engine.Action;
import charter.engine.ActionEngine;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.id.OfficeId;
import charter.id.PlayerId;
import charter.state.CharterState;

/**
 * Runs an action as a player and words what happened, for every surface a player
 * acts from: the Charter book and Discord. A refusal because the player sits on a
 * council that decides together becomes a proposal to its seats. No Minecraft
 * types, so the Discord side and plain tests use it as the book does.
 */
public final class ActAs {

    private ActAs() {
    }

    /** The sentence for a refusal: the server's own wording when it has one. */
    public static String refusal(Reason reason) {
        String key = "reason." + reason.key();
        return Text.has(key) ? Text.get(key) : reason.text();
    }

    /**
     * What running an action said: the line to show (null when it ran), and whether that line reports a proposal put to
     * the seats of the council, which is progress and not a refusal.
     */
    public record Ran(String line, boolean proposed) {
    }

    /** Null when the action ran, else the line to show the player. */
    public static String run(CharterState state, ActionEngine engine, Describe describe, PlayerId player, Action action) {
        return runDetailed(state, engine, describe, player, action).line();
    }

    public static Ran runDetailed(CharterState state, ActionEngine engine, Describe describe, PlayerId player, Action action) {
        Outcome outcome = engine.run(new Actor.Player(player), action);
        if (!(outcome instanceof Outcome.Refused(Reason reason))) return new Ran(null, false);
        if (reason.key().equals(Reason.NEEDS_SEATS.key()) && action.actingFor() != null) {
            OfficeId office = state.org(action.actingFor()).map(o -> o.governance.deciders).orElse(null);
            if (office != null) {
                Outcome proposed = engine.proposeToSeats(player, office, action);
                if (proposed instanceof Outcome.Refused(Reason why)) return new Ran(refusal(why), false);
                return new Ran(Text.get("book.seats.proposed", "office", describe.office(office)), true);
            }
        }
        return new Ran(refusal(reason), false);
    }
}
