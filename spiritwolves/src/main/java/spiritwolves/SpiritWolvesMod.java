package spiritwolves;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import thingy.api.VirtualTag;

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
        // Presence of the outer "spiritwolves" custom_data key alone identifies
        // the Spirit Stone (Thingy PLAN.md Phase 5); there is only one item kind
        // in this namespace, and its inner "bound" field is mutable state, not
        // an id.
        VirtualTag.register(SpiritStone.KEY, VirtualTag.presenceOnly("spirit_stone"));

        Tracker.register();
        // After Tracker: its SERVER_STARTED handler loads the registry the
        // sweep consults, and Fabric fires lifecycle callbacks in registration
        // order.
        WolfSweep.register();
        Binding.register();
        Summoning.register();
        Deaths.register();
        Senses.register();
        Assists.register();
        WolfKill.register();
        Fetch.register();
        RecallLock.register();
        AbilityProcs.register();
        Tricks.register();
        Training.register();
        SpiritCommands.register();
        TameWatcher.register();

        LOG.info("Spirit Wolves initialised (server-side only)");
    }
}
