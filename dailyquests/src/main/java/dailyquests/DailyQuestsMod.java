package dailyquests;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * One riddle a day, server-wide. Work out what it wants, bring it in, keep the streak.
 *
 * <p>Deliberately separate from the quiz mod. A daily has no phases, no voting and no
 * ballot, so pushing it through that mod's round engine would mean bending an
 * abstraction that fits Quiplash well onto something that is not shaped like it.
 * Two jars, two schedulers, nothing shared.
 */
public final class DailyQuestsMod implements ModInitializer {

    public static final String MOD_ID = "dailyquests";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static Quests quests;
    private static DailyState state;
    private static TurnIn turnIn;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

        quests = new Quests(configDir);
        state = new DailyState(configDir.resolve("state.json"));
        turnIn = new TurnIn(quests, state);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            quests.reload();
            state.load();
            String today = DailyState.dayKey(quests.settings().rolloverHourUtc());
            LOG.info("Daily Quests ready — {} quests in the pool, {} players tracked, today is {}",
                    quests.size(), state.size(), today);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            state.flushNow();
            state.shutdown();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            state.seen(handler.player.getUUID(), handler.player.getName().getString());
            if (quests.settings().announceOnJoin()) {
                DailyCommands.show(handler.player, quests, state);
            }
        });

        DailyCommands.register(quests, state, turnIn);

        LOG.info("Daily Quests initialised (server-side only)");
    }

    public static Quests quests() {
        return quests;
    }

    public static DailyState state() {
        return state;
    }
}
