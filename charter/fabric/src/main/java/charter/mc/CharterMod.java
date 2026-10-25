package charter.mc;

import charter.config.Knobs;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Charter: organizations, keeps and camps, and land taken by supply-line sieges.
 *
 * <p>The rules live in the {@code :core} module with nothing but the JDK on its
 * classpath. This module observes the world and applies what core decides; a
 * handler here never computes a rule (PLAN.md §2.3).
 *
 * <p>Each milestone adds its own {@code register} call below and nothing else
 * to this file, so parallel work does not collide here.
 */
public final class CharterMod implements ModInitializer {

    public static final String MOD_ID = "charter";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static Knobs knobs = new Knobs();

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        knobs = CharterConfig.readOrCreate(configDir, LOG::info, LOG::warn);
        Text.load(configDir, LOG::info, LOG::warn);

        // M7: the store's type names, the live server (load, tick, save), the commands.
        charter.mc.store.Persistence.register();
        CharterServer.register();
        charter.mc.cmd.CharterCommands.register();
        // M8: land, protection and the signals (the Mixins register through charter.mixins.json).
        charter.mc.land.LandMod.register();

        LOG.info("Charter initialised (server-side only), time scale {}", knobs.timeScale);
    }

    public static Knobs knobs() {
        return knobs;
    }
}
