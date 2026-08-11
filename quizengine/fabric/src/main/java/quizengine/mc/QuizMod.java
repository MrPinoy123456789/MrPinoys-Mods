package quizengine.mc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entrypoint. Holds the three long-lived pieces — content, leaderboard, orchestrator —
 * and hands them the server tick.
 *
 * <p>Nothing here contains game logic. Every rule lives in the {@code :engine} module,
 * which has no idea Minecraft exists.
 */
public final class QuizMod implements ModInitializer {

    public static final String MOD_ID = "quizengine";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static Content content;
    private static Leaderboard leaderboard;
    private static Orchestrator orchestrator;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

        content = new Content(configDir);
        leaderboard = new Leaderboard(configDir.resolve("leaderboard.json"));
        orchestrator = new Orchestrator(content, leaderboard);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            content.reload();
            leaderboard.load();
            LOG.info("Loaded {} trivia questions, {} prompts, {} leaderboard entries",
                    content.triviaCount(), content.promptCount(), leaderboard.size());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            orchestrator.cancel();
            leaderboard.flushNow();
            leaderboard.shutdown();
        });

        ServerTickEvents.END_SERVER_TICK.register(orchestrator::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                orchestrator.greet(handler.getPlayer()));

        QuizCommands.register(orchestrator, leaderboard, content);

        LOG.info("Quiz Engine initialised (server-side only)");
    }

    public static Content content() {
        return content;
    }

    public static Leaderboard leaderboard() {
        return leaderboard;
    }

    public static Orchestrator orchestrator() {
        return orchestrator;
    }
}
