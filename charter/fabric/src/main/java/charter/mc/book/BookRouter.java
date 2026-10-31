package charter.mc.book;

import charter.engine.Action;
import charter.engine.Reason;
import charter.id.PlayerId;
import charter.mc.ActAs;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/** What every page button does with an action: run it, and say what happened. */
final class BookRouter {

    private BookRouter() {
    }

    /** The sentence for a refusal: the server's own wording when it has one. */
    static String refusal(Reason reason) {
        return ActAs.refusal(reason);
    }

    /**
     * Runs the action as the player. A refusal because the player sits on a council
     * that decides together becomes a proposal to its seats ({@link ActAs}).
     * {@code thenShow} gets null on success, or the line to show above the rebuilt page.
     */
    static void run(ServerPlayer player, Action action, Consumer<String> thenShow) {
        CharterRuntime rt = CharterServer.runtime();
        PlayerId id = new PlayerId(player.getUUID());
        rt.state.touch(id, player.getPlainTextName(), rt.clock.now());
        thenShow.accept(ActAs.run(rt.state, rt.engine, rt.describe, id, action));
    }
}
