package lemon;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LemonMod implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("lemon");

    @Override
    public void onInitialize() {
        LemonConfig.load();
        Lemon.register();
    }
}
