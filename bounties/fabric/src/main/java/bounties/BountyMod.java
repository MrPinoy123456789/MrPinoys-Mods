package bounties;

import bounties.core.AcceptedBounty;
import bounties.core.Board;
import bounties.core.BountyDefinition;
import bounties.core.BountyMath;
import bounties.core.Completed;
import bounties.core.PlayerBounties;
import bounties.core.ProgressResult;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entrypoint for the Bounties mod.
 *
 * <p>Keeps the public board, per-player state, and reward delivery. The board itself
 * is derived from the wall clock and needs no persisted state.
 */
public final class BountyMod implements ModInitializer {

    public static final String MOD_ID = "bounties";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static BountyConfig config;
    private static BountyState state;

    private long lastWindow = Long.MIN_VALUE;
    private Board lastBoard;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

        config = new BountyConfig(configDir);
        state = new BountyState(configDir);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            config.reload();
            state.load();
            LOG.info("Loaded {} bounty definitions", config.pool().size());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            state.flushNow();
            state.shutdown();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            long window = BountyMath.windowIndex(now);
            Board board = BountyMath.boardAt(config.pool(), now);
            if (lastWindow != Long.MIN_VALUE && window != lastWindow) {
                BountyDefinition changed = null;
                if (lastBoard != null) {
                    if (board.slot1() != lastBoard.slot1()) {
                        changed = board.slot1();
                    } else if (board.slot2() != lastBoard.slot2()) {
                        changed = board.slot2();
                    }
                }
                if (changed == null) {
                    changed = board.slot1() != null ? board.slot1() : board.slot2();
                }
                if (changed != null) {
                    server.getPlayerList().broadcastSystemMessage(announce(changed), false);
                }
            }
            lastWindow = window;
            lastBoard = board;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(damageSource.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (config.pool().isEmpty()) {
                return;
            }
            String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            PlayerBounties before = state.of(player.getUUID());
            if (before.heldCount() == 0) {
                return;
            }
            ProgressResult result = before.progress(mobId);
            state.set(player.getUUID(), result.state());

            for (AcceptedBounty held : before.held()) {
                if (!mobId.equals(held.definition().mobId())) {
                    continue;
                }
                int newProgress = held.progress() + 1;
                if (newProgress >= held.definition().requiredKills()) {
                    continue;
                }
                player.sendSystemMessage(Component.literal("  " + held.definition().displayDescription()
                                + "  " + newProgress + "/" + held.definition().requiredKills())
                        .withStyle(ChatFormatting.GRAY));
                Chime.progressed(player);
            }

            for (Completed completed : result.completed()) {
                Rewards.giveDiamonds(player, completed.rewardDiamonds());
                Chime.completed(player);
                if (player.level().getServer() != null) {
                    player.level().getServer().getPlayerList().broadcastSystemMessage(
                            Component.literal(player.getName().getString() + " completed the bounty: ")
                                    .withStyle(ChatFormatting.GREEN)
                                    .append(Component.literal(completed.definition().displayDescription())
                                            .withStyle(ChatFormatting.YELLOW))
                                    .append(Component.literal(" (" + completed.rewardDiamonds() + " diamonds)")
                                            .withStyle(ChatFormatting.AQUA)),
                            false);
                }
            }
        });

        BountyCommands.register(config, state);

        LOG.info("Bounties initialised (server-side only)");
    }

    private static Component announce(BountyDefinition def) {
        return Component.literal("A new bounty has rotated onto the board: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(def.displayDescription())
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (" + def.rewardDiamonds() + " diamond"
                                + (def.rewardDiamonds() == 1 ? "" : "s") + ")  ")
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal("[ View board ]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent.RunCommand("/bounty"))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Click to open the bounty board")))));
    }

    public static BountyConfig config() {
        return config;
    }

    public static BountyState state() {
        return state;
    }
}
