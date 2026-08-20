package quizengine.mc.dialog;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import quizengine.Round;
import quizengine.mc.Orchestrator;
import quizengine.mc.QuizMod;

import java.util.Optional;

/**
 * Where a clicked dialog button lands.
 *
 * <p>Always called on the server main thread — see the mixin that dispatches here. The
 * orchestrator, the engine and the leaderboard are all single-threaded by design, and
 * the packet arrives on a netty thread, so the hop is not optional.
 *
 * <p>Nothing here decides anything. It parses a payload, checks the round is still the
 * one the player was looking at, and calls the same orchestrator methods the commands
 * call. The rules stay in the engine.
 */
public final class DialogRouter {

    private DialogRouter() {}

    /** Validates and dispatches one namespaced custom-click payload from the packet mixin. */
    public static void handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        Orchestrator orchestrator = QuizMod.orchestrator();
        if (orchestrator == null) {
            return;
        }

        if (!(payload.orElse(null) instanceof CompoundTag tag)) {
            QuizMod.LOG.warn("Dialog action {} arrived without a compound payload", id);
            return;
        }
        // The exact tag types the client sends back per input kind are worth being able
        // to see once, in play, rather than inferring.
        QuizMod.LOG.debug("Dialog action {} payload {}", id, tag);

        // A dialog is a window, and a window can be left open across a phase change.
        Round round = orchestrator.current();
        if (round == null || !round.roundId().equals(tag.getStringOr(QuizDialogs.KEY_ROUND, ""))) {
            player.sendSystemMessage(Component
                    .literal("That round has moved on. Nothing was submitted.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // Every getter here has a default rather than a throw: a malformed payload is a
        // thing a modified client can send whenever it likes, and the engine already
        // rejects a nonsense option with a message worth reading.
        switch (id.getPath()) {
            case QuizDialogs.SUBMIT_TEXT -> player.sendSystemMessage(orchestrator.submitText(
                    player, tag.getStringOr(QuizDialogs.KEY_ANSWER, "")));
            case QuizDialogs.ANSWER -> player.sendSystemMessage(orchestrator.submit(
                    player, tag.getIntOr(QuizDialogs.KEY_OPTION, -1)));
            case QuizDialogs.VOTE -> player.sendSystemMessage(orchestrator.vote(
                    player, tag.getIntOr(QuizDialogs.KEY_OPTION, -1)));
            default -> QuizMod.LOG.warn("Unknown dialog action {}", id);
        }
    }
}
