package cobblebending;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side only mod. No Mixin, no client requirement.
 */
public final class CobbleBendingMod implements ModInitializer {

    public static final String MOD_ID = "cobblebending";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static BendConfig CONFIG;

    @Override
    public void onInitialize() {
        CONFIG = new BendConfig();
        CONFIG.reload();

        ChargeTracker.register();
        BendCommands.register();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            BentBlocks.setServer(server);
            BentBlocks.tick();
            Boulder.tickAll();
        });

        ServerLifecycleEvents.SERVER_STARTING.register(BentBlocks::setServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BentBlocks.onShutdown());

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(level instanceof Level) || hand == null) {
                return InteractionResult.PASS;
            }
            if (Focus.is(player.getItemInHand(hand))) {
                player.startUsingItem(hand);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.PASS;
        });

        LOG.info("Cobble Bending initialised (server-side only)");
    }

    public static BendConfig config() {
        return CONFIG;
    }
}
