package smalltalk;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smalltalk.dialogue.DialogueLinesConfig;
import smalltalk.dialogue.HintLinesConfig;
import smalltalk.interaction.DialogHold;
import smalltalk.interaction.InteractionHandler;

/**
 * Entrypoint. Server-side only -- residents have names, personalities, and
 * something to say once you sneak- or plain-right-click them (SPEC.md).
 */
public final class SmallTalkMod implements ModInitializer {

    public static final String MOD_ID = "smalltalk";
    public static final Logger LOG = LoggerFactory.getLogger("SmallTalk");

    @Override
    public void onInitialize() {
        SmallTalkConfig.load(FabricLoader.getInstance().getConfigDir());
        DialogueLinesConfig.load(FabricLoader.getInstance().getConfigDir());
        HintLinesConfig.load(FabricLoader.getInstance().getConfigDir());

        InteractionHandler.register();
        SmallTalkCommands.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> DialogHold.serverTick());

        LOG.info("Small Talk initialised (server-side only)");
    }
}
