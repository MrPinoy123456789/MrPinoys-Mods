package rehome;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint. Server-side only -- villagers follow you home and move into
 * the house you built for them (SPEC.md).
 */
public final class RehomeMod implements ModInitializer {

    public static final String MOD_ID = "rehome";
    public static final Logger LOG = LoggerFactory.getLogger("Rehome");

    @Override
    public void onInitialize() {
        RehomeConfig.load(FabricLoader.getInstance().getConfigDir());

        GiftHandler.register();
        FollowBehavior.register();
        ClaimWatcher.register();

        LOG.info("Rehome initialised (server-side only)");
    }
}
