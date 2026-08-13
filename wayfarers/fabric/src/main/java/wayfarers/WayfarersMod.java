package wayfarers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entrypoint for Wayfarers.
 *
 * <p>World encounters that wait for the player to approach. Server-side only;
 * vanilla clients install nothing.
 */
public final class WayfarersMod implements ModInitializer {

    public static final String MOD_ID = "wayfarers";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static WayfarersConfig config;
    private static Bubbles bubbles;
    private static Encounters encounters;
    private static OrphanSweep orphanSweep;
    private static Dialogue dialogue;
    private static int flushCounter;

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOG.info("Wayfarers {} initialised (server-side only)", version);

        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = new WayfarersConfig(configDir);
        bubbles = new Bubbles();
        encounters = new Encounters(config, bubbles);
        orphanSweep = OrphanSweep.register(encounters);
        dialogue = new Dialogue(config, bubbles);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            config.reload();
            TraderRegistry.load(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            encounters.endAll();
            TraderRegistry.flushAll();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                encounters.endFor(handler.getPlayer().getUUID()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            encounters.tick();
            orphanSweep.tick(encounters);
            dialogue.tick(encounters);
            if (++flushCounter % 600 == 0) {
                TraderRegistry.flushDirty();
            }
        });

        Hostile.register(encounters, dialogue);
        Patient.register(encounters);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, dealt, taken, blocked) -> {
            if (entity instanceof ServerPlayer player) {
                dialogue.hush(player);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            Encounters.Active active = encounters.activeForEntity(entity.getUUID());
            if (active != null) {
                dialogue.onDeath(active);
                Drops.roll(active, entity, source);
                encounters.endFor(active.player.getUUID());
            }
        });

        WayfarerCommands.register(config, encounters);
    }

    public static WayfarersConfig config() { return config; }
    public static Encounters encounters() { return encounters; }
}
