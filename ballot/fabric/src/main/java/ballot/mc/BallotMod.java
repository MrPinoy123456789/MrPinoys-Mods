package ballot.mc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import ballot.Poll;
import ballot.PollState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Milestone 1: the model, the folder, and enough commands to drive a whole poll from
 * chat. No world interaction yet — ballot boxes, signs and the wand come next.
 *
 * <p>The poll model lives in the {@code :core} module and has no Minecraft on its
 * classpath, so the rules are testable in milliseconds without a server.
 */
public final class BallotMod implements ModInitializer {

    public static final String MOD_ID = "ballot";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static PollStore store;
    private static Deadlines deadlines;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        store = new PollStore(configDir, LOG::warn);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            store.loadAll();
            store.current().ifPresent(poll -> Signs.refresh(server, poll));
            LOG.info("Ballot ready — {} poll(s) loaded", store.size());
        });

        deadlines = new Deadlines(store);
        ServerTickEvents.END_SERVER_TICK.register(deadlines::tick);

        // Nudge only joining players who still have an open vote to cast.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                nudge(handler.player));

        Interactions.register(store);
        Breaks.register(store);
        BallotCommands.register(store);
        LOG.info("Ballot initialised (server-side only)");
    }

    public static PollStore store() {
        return store;
    }

    /**
     * On join, mention only votes this player has not acted on. Someone who has already
     * voted in everything running should hear nothing — a notification that fires every
     * time is one people learn to ignore.
     */
    private static void nudge(net.minecraft.server.level.ServerPlayer player) {
        String uuid = player.getUUID().toString();
        Optional<Poll> poll = store.current();
        if (poll.isEmpty()
                || poll.get().state() != PollState.VOTING
                || poll.get().voteOf(uuid).isPresent()) {
            return;
        }
        player.sendSystemMessage(Component.literal("Voting is open: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Screens.button("[ " + poll.get().title() + " ]", "/ballot")));
    }

    /**
     * The op check for contexts with no {@code CommandSourceStack} — e.g. a player
     * holding a wand in the world. Command handlers with a source available should
     * use {@code source.hasPermission(Commands.LEVEL_GAMEMASTERS)} instead, matching
     * the rest of the mod set.
     */
    public static boolean isOp(MinecraftServer server, ServerPlayer player) {
        return player == null || server.getPlayerList().isOp(player.nameAndId());
    }
}
