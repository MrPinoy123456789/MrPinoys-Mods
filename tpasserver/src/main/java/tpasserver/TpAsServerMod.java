package tpasserver;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All the behaviour lives in {@link tpasserver.mixin.CommandsMixin}; this
 * entrypoint only exists because Fabric requires one.
 */
public final class TpAsServerMod implements ModInitializer {

    public static final String MOD_ID = "tpasserver";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("TP As Server ready — /tp and /teleport now run with server permissions");
    }
}
