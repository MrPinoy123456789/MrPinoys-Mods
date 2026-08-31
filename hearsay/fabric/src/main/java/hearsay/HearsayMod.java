package hearsay;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entrypoint for Hearsay.
 *
 * <p>Server-side only; vanilla clients install nothing.
 */
public final class HearsayMod implements ModInitializer {

    public static final String MOD_ID = "hearsay";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static HearsayConfig config;
    private static Listeners listeners;
    private static Bubbles bubbles;
    private static Scenes scenes;
    private static Speech speech;
    private static Mute mute;
    private static Reactions reactions;

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOG.info("Hearsay {} initialised (server-side only)", version);

        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = new HearsayConfig(configDir);
        listeners = new Listeners(config);
        bubbles = new Bubbles();
        mute = new Mute();
        scenes = new Scenes(config, bubbles, mute);
        speech = new Speech(config, bubbles, scenes, mute);
        reactions = new Reactions(config, speech);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            config.reload();
            LOG.info("Hearsay config loaded");
            // Bubble TextDisplays are saved entities and survive a restart; the
            // in-memory bubble map does not. Purge orphans before any new speech
            // can stack on top of them.
            bubbles.purgeOrphans(server);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            listeners.tick(server);
            scenes.tick(server, listeners);
            speech.tick(server, listeners);
            reactions.tick(server, listeners);
        });

        // Any damage to a player starts the configured combat hush immediately, so
        // ambient chatter cannot obscure a fight even when the hit was fully blocked.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, dealt, taken, blocked) -> {
            if (entity instanceof ServerPlayer player) {
                speech.hush(player.getUUID(), config.settings().suppressAfterDamageSeconds());
            }
        });

        // The post-death hook still provides the villager's position and profession,
        // allowing nearby survivors to react even though the speaker itself is gone.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Villager villager) {
                reactions.onVillagerDeath(villager);
            }
        });

        // Fabric exposes no completed-trade callback here. This reports opening or
        // otherwise using a villager, not a successful trade, and ignores the duplicate
        // off-hand callback without reaching into vanilla trade internals.
        UseEntityCallback.EVENT.register((Player player, net.minecraft.world.level.Level level,
                                          InteractionHand hand, Entity entity,
                                          EntityHitResult hitResult) -> {
            if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer
                    && entity instanceof Villager villager) {
                reactions.onVillagerInteraction(villager);
            }
            return InteractionResult.PASS;
        });

        HearsayCommands.register(config, listeners, speech, scenes, mute);
    }

    public static HearsayConfig config() { return config; }
    public static Listeners listeners() { return listeners; }
    public static Bubbles bubbles() { return bubbles; }
    public static Scenes scenes() { return scenes; }
    public static Speech speech() { return speech; }
    public static Mute mute() { return mute; }
    public static Reactions reactions() { return reactions; }
}
