package charterxaero;

import charterxaero.model.Versions;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Which of Xaero's maps are here and new enough for the hooks this mod was built against. Nothing that names a Xaero
 * class may run unless the matching check here says yes: without Xaero's maps this mod only keeps the data.
 */
public final class XaeroSupport {

    public static final String WORLD_MAP = "xaeroworldmap";
    public static final String MINIMAP = "xaerominimap";
    /** The oldest versions the highlighter and waypoint hooks were checked against (built with 1.47.2 and 26.6.2). */
    public static final String WORLD_MAP_MIN = "1.47.0";
    public static final String MINIMAP_MIN = "26.6.0";

    private static Boolean worldMap;
    private static Boolean minimap;

    private XaeroSupport() {
    }

    /** Xaero's World Map is loaded and new enough. */
    public static synchronized boolean worldMap() {
        if (worldMap == null) worldMap = usable(WORLD_MAP, WORLD_MAP_MIN);
        return worldMap;
    }

    /** Xaero's Minimap is loaded and new enough. */
    public static synchronized boolean minimap() {
        if (minimap == null) minimap = usable(MINIMAP, MINIMAP_MIN);
        return minimap;
    }

    /** The loaded version of a mod, or null. */
    public static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    private static boolean usable(String modId, String min) {
        String have = version(modId);
        if (have == null) return false;
        if (Versions.atLeast(have, min)) return true;
        // Its own logger: this runs while mixins are prepared, before game classes may be loaded.
        org.slf4j.LoggerFactory.getLogger("charter_xaero").warn("Charter Maps needs {} {} or newer and found {}; kingdoms will not show on it.", modId, min, have);
        return false;
    }
}
