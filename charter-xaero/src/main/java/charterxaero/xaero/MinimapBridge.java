package charterxaero.xaero;

import charterxaero.CharterMapsClient;
import charterxaero.model.Colours;
import charterxaero.model.Tooltips;
import charterxaero.wire.MapModel;
import charterxaero.wire.Runs;
import net.minecraft.resources.Identifier;
import xaero.common.minimap.highlight.DimensionHighlighterHandler;
import xaero.common.minimap.highlight.HighlighterRegistry;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.MinimapWorld;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The thin binding to Xaero's Minimap: the kingdoms' highlighter, redraw requests, and markers as Xaero's third party
 * waypoints (shown on the Minimap, in its waypoint list and, with the World Map installed, on the World Map). Only called
 * when {@link charterxaero.XaeroSupport#minimap()} says yes.
 */
public final class MinimapBridge {

    /** Where our waypoints live in the Minimap, apart from the player's own. */
    static final Identifier ORIGIN = Identifier.fromNamespaceAndPath("charter_xaero", "markers");

    private static boolean warned;
    private static int[] palette;

    private MinimapBridge() {
    }

    /** Adds the kingdoms' highlighter to a Minimap registry that is about to be frozen. */
    public static void register(Object registry) {
        try {
            ((HighlighterRegistry) registry).register(new MinimapKingdoms());
            CharterMapsClient.LOG.info("Charter Maps: kingdoms added to Xaero's Minimap.");
        } catch (Throwable broken) {
            warnOnce("could not add the kingdoms to Xaero's Minimap", broken);
        }
    }

    private static MinimapSession session() {
        return BuiltInHudModules.MINIMAP.getCurrentSession();
    }

    /** Asks the Minimap to redraw these regions. It keeps one dimension at a time, so every region is passed on. */
    public static void refresh(Map<String, Set<Long>> regions) {
        if (regions.isEmpty()) return;
        try {
            MinimapSession session = session();
            if (session == null || session.getProcessor() == null || session.getProcessor().getMinimapWriter() == null) return;
            DimensionHighlighterHandler handler = session.getProcessor().getMinimapWriter().getDimensionHighlightHandler();
            if (handler == null) return;
            for (Set<Long> set : regions.values()) {
                for (long r : set) handler.requestRefresh(Runs.x(r), Runs.z(r));
            }
        } catch (Throwable broken) {
            warnOnce("could not ask Xaero's Minimap to redraw", broken);
        }
    }

    /**
     * Shows the live markers of the player's dimension as waypoints, replacing the ones shown before. Returns false when
     * the Minimap has no world yet (try again later).
     */
    public static boolean showMarkers(List<MapModel.Marker> live, String dimension) {
        try {
            MinimapSession session = session();
            if (session == null || session.getWorldManager() == null) return false;
            MinimapWorld world = session.getWorldManager().getCurrentWorld();
            if (world == null || world.getContainer() == null) return false;
            ThirdPartyWaypoints ours = world.getContainer().getThirdPartyWaypointManager().get(ORIGIN);
            ours.clear();
            for (MapModel.Marker m : live) {
                if (!m.dimension().equals(dimension)) continue;
                String label = Tooltips.marker(m);
                Waypoint waypoint = new Waypoint(m.x(), m.y(), m.z(), label, Tooltips.initials(label), colour(m.colour()),
                        WaypointPurpose.NORMAL, true, true);
                ours.add(m.id(), waypoint);
            }
            return true;
        } catch (Throwable broken) {
            warnOnce("could not show map markers on Xaero's Minimap", broken);
            return true;
        }
    }

    /** Removes our waypoints, on leaving a server. */
    public static void clearMarkers() {
        try {
            MinimapSession session = session();
            if (session == null || session.getWorldManager() == null) return;
            MinimapWorld world = session.getWorldManager().getCurrentWorld();
            if (world == null || world.getContainer() == null) return;
            world.getContainer().getThirdPartyWaypointManager().clearOrigin(ORIGIN);
        } catch (Throwable broken) {
            warnOnce("could not clear map markers from Xaero's Minimap", broken);
        }
    }

    /** The waypoint colour nearest to a marker's colour: Xaero's waypoints take one of a fixed list. */
    static WaypointColor colour(int rgb) {
        WaypointColor[] all = WaypointColor.values();
        if (palette == null) {
            int[] p = new int[all.length];
            for (int i = 0; i < all.length; i++) p[i] = all[i].getHex() & 0xFFFFFF;
            palette = p;
        }
        return all[Colours.nearest(rgb, palette)];
    }

    private static void warnOnce(String what, Throwable why) {
        if (warned) return;
        warned = true;
        CharterMapsClient.LOG.warn("Charter Maps {}: {}", what, why.toString());
    }
}
