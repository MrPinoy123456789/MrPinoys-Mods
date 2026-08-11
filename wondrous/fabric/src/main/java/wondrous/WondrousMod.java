package wondrous;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wondrous.api.WondrousItems;

/**
 * Utility items that carry a station in your pocket, plus boots that let you fly
 * and tools that break a 3x3. Server-side only -- vanilla clients need nothing
 * installed.
 *
 * <p>Fourth mod in the set and the first meant to be consumed by the others. The
 * items are vanilla items carrying a {@code minecraft:custom_data} marker rather
 * than registry entries, which is what keeps vanilla clients connecting: nothing
 * new appears in a synced registry.
 *
 * <p>No Mixins. Everything runs off Fabric API event callbacks.
 */
public final class WondrousMod implements ModInitializer {

    public static final String MOD_ID = "wondrous";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static ItemRegistry registry;

    @Override
    public void onInitialize() {
        registry = new ItemRegistry();
        WondrousItems.Holder.set(registry);

        FlyingBoots.register();
        Stations.register(registry);
        AreaBreak.register(registry);
        BoomerangBall.register();
        WondrousCommands.register(registry);

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> AreaBreak.forget(handler.player.getUUID()));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOG.info("Wondrous Items ready -- {} items available", registry.all().size());
            // 26.2's MinecraftServer has no isFlightAllowed(), so this cannot be a
            // real check. It is the one config mistake that makes the headline item
            // look broken, so it gets said out loud regardless.
            LOG.info("Up Up And Bye Boots require allow-flight=true in server.properties.");
        });

        LOG.info("Wondrous Items initialised (server-side only)");
    }

    public static ItemRegistry registry() {
        return registry;
    }
}
