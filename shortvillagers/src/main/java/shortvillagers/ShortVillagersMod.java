package shortvillagers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint. Server-side only. The actual work is done by
 * {@link shortvillagers.mixin.VillagerDimensionsMixin}; this class just loads
 * the config so the mixin can read the target height.
 */
public final class ShortVillagersMod implements ModInitializer {

    public static final String MOD_ID = "shortvillagers";
    public static final Logger LOG = LoggerFactory.getLogger("Short Villagers");

    @Override
    public void onInitialize() {
        ShortVillagersConfig.load(FabricLoader.getInstance().getConfigDir());

        LOG.info("Short Villagers initialised (server-side only, target height {})",
                ShortVillagersConfig.targetHeight());
    }
}
