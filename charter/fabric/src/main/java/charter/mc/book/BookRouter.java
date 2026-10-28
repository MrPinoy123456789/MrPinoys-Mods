package charter.mc.book;

import charter.engine.Action;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.id.OfficeId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/** What every page button does with an action: run it, and say what happened. */
final class BookRouter {

    private BookRouter() {
    }

    /** The sentence for a refusal: the server's own wording when it has one. */
    static String refusal(Reason reason) {
        String key = "reason." + reason.key();
        return Text.has(key) ? Text.get(key) : reason.text();
    }

    /**
     * Runs the action as the player. A refusal because the player sits on a council
     * that decides together becomes a proposal to its seats. {@code thenShow} gets
     * null on success, or the line to show above the rebuilt page.
     */
    static void run(ServerPlayer player, Action action, Consumer<String> thenShow) {
        CharterRuntime rt = CharterServer.runtime();
        PlayerId id = new PlayerId(player.getUUID());
        rt.state.touch(id, player.getPlainTextName(), rt.clock.now());
        Outcome outcome = rt.engine.run(new Actor.Player(id), action);
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            if (reason.key().equals(Reason.NEEDS_SEATS.key()) && action.actingFor() != null) {
                OfficeId office = rt.state.org(action.actingFor()).map(o -> o.governance.deciders).orElse(null);
                if (office != null) {
                    Outcome proposed = rt.engine.proposeToSeats(id, office, action);
                    if (proposed instanceof Outcome.Refused(Reason why)) {
                        thenShow.accept(refusal(why));
                    } else {
                        thenShow.accept(Text.get("book.seats.proposed", "office", rt.describe.office(office)));
                    }
                    return;
                }
            }
            thenShow.accept(refusal(reason));
            return;
        }
        thenShow.accept(null);
    }
}
