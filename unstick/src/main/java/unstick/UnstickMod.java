package unstick;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint. Server-side only. Unstick watches loaded villagers and, when
 * vanilla's pathfinder has wedged one onto a thin block (the MC-96319 family:
 * flower pots, lanterns, carpets under low ceilings, and so on), gives it a
 * small teleport nudge toward wherever it was trying to go. It does not change
 * routing; it only ends the stuck state faster than vanilla would (which is
 * never).
 */
public final class UnstickMod implements ModInitializer {

    public static final String MOD_ID = "unstick";
    public static final Logger LOG = LoggerFactory.getLogger("Unstick");

    @Override
    public void onInitialize() {
        UnstickConfig.load(FabricLoader.getInstance().getConfigDir());
        StuckWatcher.register();

        LOG.info("Unstick initialised (server-side only)");
    }
}
