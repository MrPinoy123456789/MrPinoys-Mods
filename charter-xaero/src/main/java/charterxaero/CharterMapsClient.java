package charterxaero;

import charterxaero.model.KingdomIndex;
import charterxaero.model.MapStore;
import charterxaero.wire.MapState;
import charterxaero.xaero.MinimapBridge;
import charterxaero.xaero.WorldMapBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * Charter Maps: receives a Charter server's kingdom map and shows it on Xaero's World Map and Minimap. Registering the
 * map channel is what tells the server this client wants it; servers without Charter never send anything, and then
 * this mod does nothing. Without Xaero's maps it still keeps the data (see {@code /chartermap}) and draws nothing.
 */
public final class CharterMapsClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("charter_xaero");
    /** What this client knows of the map; Xaero's highlighters read its index. */
    public static final MapStore STORE = new MapStore();

    private static boolean markersDirty = true;
    private static String shownDimension;
    private static boolean warnedBad;
    private static long ticks;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(MapPayload.TYPE, MapPayload.CODEC);
        // Fabric runs play handlers on the game thread.
        ClientPlayNetworking.registerGlobalReceiver(MapPayload.TYPE, (payload, context) -> receive(payload.bytes()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> leave());
        ClientTickEvents.END_CLIENT_TICK.register(CharterMapsClient::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("chartermap").executes(c -> {
                    for (String line : status()) c.getSource().sendFeedback(Component.literal(line));
                    return 1;
                })));

        // Without the Minimap there are no waypoints, so markers are drawn as a coloured chunk on the World Map.
        STORE.markerChunks(!XaeroSupport.minimap(), System.currentTimeMillis());
        STORE.listen(CharterMapsClient::changed);
        LOG.info("Charter Maps ready (World Map: {}, Minimap: {}).", XaeroSupport.worldMap() ? "on" : "off",
                XaeroSupport.minimap() ? "on" : "off");
    }

    private static void receive(byte[] bytes) {
        MapStore.Outcome outcome = STORE.receive(bytes, System.currentTimeMillis());
        if ((outcome == MapStore.Outcome.BAD || outcome == MapStore.Outcome.OUT_OF_ORDER) && !warnedBad) {
            warnedBad = true;
            LOG.warn("Charter Maps dropped a map message: {}. The map refreshes when you next join.", STORE.lastError());
        }
    }

    /** Tells Xaero's maps to redraw what changed, and the waypoints to follow. */
    private static void changed(KingdomIndex before, KingdomIndex after, MapState state) {
        Map<String, Set<Long>> regions = KingdomIndex.changedRegions(before, after);
        if (XaeroSupport.worldMap()) WorldMapBridge.refresh(regions);
        if (XaeroSupport.minimap()) MinimapBridge.refresh(regions);
        markersDirty = true;
    }

    private static void leave() {
        STORE.clear();
        if (XaeroSupport.minimap()) MinimapBridge.clearMarkers();
        shownDimension = null;
        markersDirty = true;
        warnedBad = false;
    }

    private static void tick(Minecraft client) {
        ticks++;
        if (ticks % 20 == 0) STORE.prune(System.currentTimeMillis());
        if (!XaeroSupport.minimap() || client.level == null) return;
        String dimension = client.level.dimension().identifier().toString();
        if (!dimension.equals(shownDimension)) markersDirty = true;
        // Waypoints live per dimension in the Minimap, so they are put back after a dimension change as well.
        if (markersDirty && ticks % 10 == 0
                && MinimapBridge.showMarkers(STORE.state().liveMarkers(System.currentTimeMillis()), dimension)) {
            markersDirty = false;
            shownDimension = dimension;
        }
    }

    /** What {@code /chartermap} prints. */
    static String[] status() {
        KingdomIndex index = STORE.index();
        MapState state = STORE.state();
        String received = state.seq() < 0 ? "No kingdom map from this server (it may not run Charter, or has the map off)."
                : "Kingdom map: " + index.kingdoms().size() + " kingdom(s), " + index.chunkCount() + " chunk(s), "
                + state.liveMarkers(System.currentTimeMillis()).size() + " marker(s).";
        String maps = "Xaero's World Map: " + (XaeroSupport.worldMap() ? "drawing" : "not installed")
                + ". Xaero's Minimap: " + (XaeroSupport.minimap() ? "drawing" : "not installed") + ".";
        String error = STORE.lastError();
        return error.isEmpty() ? new String[]{received, maps} : new String[]{received, maps, "Last dropped message: " + error};
    }
}
