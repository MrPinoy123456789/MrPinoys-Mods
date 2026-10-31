package charter.mc.land;

import charter.mc.CharterServer;
import charter.mc.cmd.LandCommands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * M8's one register call, from {@code CharterMod.onInitialize}: the saved fixture
 * block, the protection events, the signals and the land commands. The Mixins
 * register themselves through {@code charter.mixins.json}.
 */
public final class LandMod {

    private LandMod() {
    }

    public static void register() {
        Fixtures.registerBlock();
        Guard.register();
        Signals.register();
        Crossings.register();
        LandCommands.register();
        // The runtime is made when the server starts; follow its history once it is up.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (CharterServer.running()) CharterServer.runtime().state.history.listen(Placement::onHistory);
        });
    }
}
