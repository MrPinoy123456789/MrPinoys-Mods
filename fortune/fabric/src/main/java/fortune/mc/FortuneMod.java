package fortune.mc;

import fortune.core.Machine;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Fantasy's Fortune: a daily slot machine login bonus for the kingdom server. Server side only. It runs with or
 * without Charter Currency (Valor through its {@code earn} command, the coin stake through its bank), Charter and
 * Floodgate; it imports nothing from Charter.
 */
public final class FortuneMod implements ModInitializer {

    public static final String MOD_ID = "fortune";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        Machine machine = ConfigFile.loadOrDefault(configDir);
        FloodgateAdapter.installIfPresent();

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> FortuneCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Machine current = ConfigFile.load(configDir, machine);
            Path worldDir = server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID);
            Fortune.set(new FortuneService(server, configDir, worldDir, current, StakeBank.detect()));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            FortuneService service = Fortune.service();
            if (service != null) service.stop();
            Fortune.set(null);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            FortuneService service = Fortune.service();
            if (service != null) service.tick();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            FortuneService service = Fortune.service();
            if (service != null) service.onJoin(handler.getPlayer());
        });

        // A right-click on a Fortune Machine opens the machine. Sneaking with something in hand builds against it as
        // vanilla does. Both hands of the click are answered, so the off hand never places a block into it.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            FortuneService service = Fortune.service();
            if (service == null || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (!service.machines().isMachine(level, hit.getBlockPos())) return InteractionResult.PASS;
            if (player.isShiftKeyDown() && !player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
            if (hand == InteractionHand.MAIN_HAND) service.openFor(sp, hit.getBlockPos());
            return InteractionResult.SUCCESS_SERVER;
        });
    }
}
