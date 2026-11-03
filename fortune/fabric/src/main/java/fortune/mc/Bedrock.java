package fortune.mc;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Bedrock players, as far as Fortune needs them: who they are (frame pacing, the join prompt) and a modal form with
 * two buttons. Without Floodgate nobody is Bedrock and no form is ever sent. The answer of a form arrives on a Netty
 * thread; {@link #modal} moves it onto the server thread before anything runs.
 */
final class Bedrock {

    /** What {@link FloodgateAdapter} implements. */
    interface Sender {
        boolean isBedrock(UUID player);

        /** Sends a modal form; {@code answer} gets 0 for the first button, 1 for the second, -1 when closed. */
        boolean modal(UUID player, String title, String content, String first, String second, IntConsumer answer);
    }

    private static volatile Sender sender;

    private Bedrock() {
    }

    static void install(Sender s) {
        sender = s;
    }

    static boolean isBedrock(UUID player) {
        Sender s = sender;
        if (s == null) return false;
        try {
            return s.isBedrock(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sends a modal form, answering on the server thread. False when no form could be sent. */
    static boolean modal(MinecraftServer server, UUID player, String title, String content, String first, String second,
                         IntConsumer answer) {
        Sender s = sender;
        if (s == null) return false;
        try {
            return s.modal(player, title, content, first, second, button -> server.execute(() -> answer.accept(button)));
        } catch (Throwable t) {
            FortuneMod.LOG.warn("Could not send a Bedrock form: {}", t.toString());
            return false;
        }
    }
}
