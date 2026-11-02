package charterxaero.xaero;

import charterxaero.CharterMapsClient;
import charterxaero.wire.Runs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.highlight.DimensionHighlighterHandler;
import xaero.map.highlight.HighlighterRegistry;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

import java.util.Map;
import java.util.Set;

/** The thin binding to Xaero's World Map. Only called when {@link charterxaero.XaeroSupport#worldMap()} says yes. */
public final class WorldMapBridge {

    private static boolean warned;

    private WorldMapBridge() {
    }

    /** Adds the kingdoms' highlighter to a World Map registry that is about to be frozen. */
    public static void register(Object registry) {
        try {
            ((HighlighterRegistry) registry).register(new WorldMapKingdoms());
            CharterMapsClient.LOG.info("Charter Maps: kingdoms added to Xaero's World Map.");
        } catch (Throwable broken) {
            warnOnce("could not add the kingdoms to Xaero's World Map", broken);
        }
    }

    /** Asks the World Map to redraw these regions (by dimension, packed with {@link Runs#pack}). */
    public static void refresh(Map<String, Set<Long>> regions) {
        if (regions.isEmpty()) return;
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session == null) return;
            MapProcessor processor = session.getMapProcessor();
            MapWorld world = processor == null ? null : processor.getMapWorld();
            if (world == null) return;
            for (Map.Entry<String, Set<Long>> e : regions.entrySet()) {
                Identifier id = Identifier.tryParse(e.getKey());
                if (id == null) continue;
                MapDimension dimension = world.getDimension(ResourceKey.create(Registries.DIMENSION, id));
                DimensionHighlighterHandler handler = dimension == null ? null : dimension.getHighlightHandler();
                if (handler == null) continue;
                // Clearing a region's cached hash makes the map compare it again and redraw it if it changed; it also
                // tells the Minimap, when both are installed.
                for (long r : e.getValue()) handler.clearCachedHash(Runs.x(r), Runs.z(r));
            }
        } catch (Throwable broken) {
            warnOnce("could not ask Xaero's World Map to redraw", broken);
        }
    }

    private static void warnOnce(String what, Throwable why) {
        if (warned) return;
        warned = true;
        CharterMapsClient.LOG.warn("Charter Maps {}: {}", what, why.toString());
    }
}
