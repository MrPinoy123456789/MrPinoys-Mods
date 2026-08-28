package thingy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import thingy.api.VirtualItems;
import thingy.api.VirtualTag;

/**
 * Virtual object identity framework: stable, namespaced, server-authoritative
 * identity for custom objects represented as vanilla carriers (PLAN.md, "Core
 * design principle"). Server-side only: vanilla clients need nothing
 * installed.
 *
 * <p>Phase 1 hosts one namespace, {@code wondrous}, with full parity for every
 * item {@code WondrousMod} registered. {@code Stations}, {@code CraftStation},
 * {@code LazySprinkler}, and the rest are direct ports: same ids, same
 * {@code custom_data} shape, same {@code wondrous:} attribute modifier ids, so
 * a stack made by the old wondrous jar is byte-identical to one made here.
 *
 * <p>No Mixins. Everything runs off Fabric API event callbacks.
 */
public final class ThingyMod implements ModInitializer {

    public static final String MOD_ID = "thingy";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static ItemRegistry registry;

    @Override
    public void onInitialize() {
        // Shape preservation (PLAN.md Phase 1, hard constraint 1): the wondrous
        // namespace keeps WondrousTag's exact shape, a bare string under key
        // "wondrous". Registered before anything can stamp or read a stack.
        VirtualTag.register("wondrous", VirtualTag.BARE_STRING);

        registry = new ItemRegistry();
        VirtualItems.Holder.set(registry);

        Aura.register();
        WondrousState.register();
        new CraftStation(registry).register();
        new LazySprinkler(registry).register();
        new LinkWand().register();
        new CarryGlove().register();
        FlyingBoots.register();
        Stations.register(registry);
        PeekBox.register(registry);
        VoidBin.register(registry);
        RecipeGuard.register();
        ChuckIt.register(registry);
        TidyUp.register(registry);
        BigLazyHoe.register(registry);
        Growth.register(registry);
        Mortar.register(registry);
        Restock.register();
        AuraEffects.register();
        AreaBreak.register(registry);
        BoomerangBall.register();
        ThingyCommands.register(registry);

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> AreaBreak.forget(handler.player.getUUID()));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOG.info("Thingy ready: {} items available", registry.all().size());
            // 26.2's MinecraftServer has no isFlightAllowed(), so this cannot be a
            // real check. It is the one config mistake that makes the headline item
            // look broken, so it gets said out loud regardless.
            LOG.info("Up Up And Bye Boots require allow-flight=true in server.properties.");
        });

        LOG.info("Thingy initialised (server-side only)");
    }

    public static ItemRegistry registry() {
        return registry;
    }
}
