package spiritwolves;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Spirit Stone binds a tamed wolf's soul permanently. Lethal damage burns a
 * charge instead of killing it. Server-side only -- vanilla clients need
 * nothing installed, since the stone is a vanilla item marked with custom_data.
 */
public final class SpiritWolvesMod implements ModInitializer {

    public static final String MOD_ID = "spiritwolves";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Tracker.register();
        Binding.register();
        Summoning.register();
        Deaths.register();
        Senses.register();
        WolfKill.register();
        Fetch.register();
        RecallLock.register();
        VerbProcs.register();
        SpiritCommands.register();

        LOG.info("Spirit Wolves initialised (server-side only)");
    }
}
