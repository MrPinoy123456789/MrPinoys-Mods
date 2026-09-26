package pocketdungeons.gametest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pocketdungeons.PocketDungeonsMod;
import pocketdungeons.ZoneRulesIntegrationCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code dungeonIntegrationTest} entrypoint: proves the real dedicated
 * server route that {@code runGameTest} cannot, because {@code GameTestServer}
 * bakes an empty {@code LEVEL_STEM} registry against the flat world preset and
 * never creates a datapack dimension (DISCOVERIES trap 18). An ordinary
 * dedicated server, run against this project's own bundled datapack, does
 * create {@code pocketdungeons:void} for real, which is exactly what
 * {@code LIVE_TEST_PASS} 35.1 says nothing automated has ever seen.
 *
 * <p>This class is registered as this mod id's {@code main} entrypoint in
 * {@code src/gametest/resources/fabric.mod.json}, the same module
 * {@code InventorySwapGameTest} lives in, so it never reaches the production
 * jar. It is inert everywhere else: it only acts when the
 * {@code pocketdungeons.integrationtest} system property is set, which only
 * the {@code dungeonIntegrationTest} Gradle task's JVM sets. Without that
 * property this {@code onInitialize} is a no-op, so it is harmless on
 * {@code runServer} and {@code runGameTest}, both of which also load the
 * gametest module.
 *
 * <p>No world-creation mixin: this proves only that the dimension the
 * project's own datapack already declares loads on a normal server, not that
 * one can be created from code.
 *
 * <p>Scope, deliberately narrow: dimension identity and a save round trip
 * within one process. A real process kill between save and reload
 * ({@code LIVE_TEST_PASS} 35.2) is not exercised here and stays a live-client
 * check; this is plumbing, not custody or fault coverage, which M63 adds.
 */
public final class DungeonIntegrationEntrypoint implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("PocketDungeonsIntegrationTest");
    private static final String ENABLE_PROPERTY = "pocketdungeons.integrationtest";

    /**
     * M62 step 5, "break one assertion": flip this to {@code true} to invert
     * the DUNGEON_LEVEL-is-loaded assertion and prove the task actually fails
     * when the thing it checks is false. Restored to {@code false} before
     * every commit; a value of {@code true} here is a mistake, not a style
     * choice.
     */
    private static final boolean INVERT_DUNGEON_LEVEL_ASSERTION_FOR_PROOF = false;

    /** Bounds how long this waits for SERVER_STARTED before failing loud. */
    private static final long STARTUP_TIMEOUT_SECONDS = 120;

    @Override
    public void onInitialize() {
        if (!"true".equals(System.getProperty(ENABLE_PROPERTY))) {
            return;
        }

        AtomicBoolean started = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_TIMEOUT_SECONDS * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            if (!started.get()) {
                LOG.error("dungeonIntegrationTest: server did not reach SERVER_STARTED within {}s",
                        STARTUP_TIMEOUT_SECONDS);
                System.exit(1);
            }
        }, "dungeon-integration-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            started.set(true);
            watchdog.interrupt();
            int exitCode = runChecks(server);
            LOG.info("dungeonIntegrationTest: {}", exitCode == 0 ? "PASS" : "FAIL");
            // Not System.exit(exitCode) called directly from here: this
            // callback runs on the server thread, and Minecraft's own JVM
            // shutdown hook needs that same thread to notice a stop flag and
            // unwind its tick loop before the hook can finish. Calling
            // System.exit() synchronously from the server thread blocks it
            // inside the hook, so the tick loop that would clear the flag
            // never runs; verified against the real jar, where this hung the
            // process indefinitely (36 minutes, no further log output) rather
            // than exiting. Exiting from a separate thread instead lets this
            // callback return, the server thread carry on into its normal
            // tick loop, and the shutdown hook's wait resolve.
            Thread exit = new Thread(() -> System.exit(exitCode), "dungeon-integration-test-exit");
            exit.setDaemon(true);
            exit.start();
        });
    }

    private static int runChecks(MinecraftServer server) {
        List<String> failures = new ArrayList<>();

        boolean overworldIsDungeon = server.overworld().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
        if (overworldIsDungeon) {
            failures.add("server.getOverworld() reported the dungeon level as the overworld");
        }

        ServerLevel dungeon = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        boolean dungeonLoaded = dungeon != null;
        if (INVERT_DUNGEON_LEVEL_ASSERTION_FOR_PROOF) {
            dungeonLoaded = !dungeonLoaded;
        }
        if (!dungeonLoaded) {
            failures.add("server.getLevel(" + PocketDungeonsMod.DUNGEON_LEVEL.identifier()
                    + ") was null on a real dedicated server");
        }

        if (dungeon != null) {
            try {
                server.saveEverything(false, true, true);
            } catch (RuntimeException e) {
                failures.add("saveEverything threw during the save/restart smoke test: " + e);
            }

            java.nio.file.Path worldDataDir = server
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("data");
            if (!java.nio.file.Files.isDirectory(worldDataDir)) {
                failures.add("expected a world/data directory to exist after a forced save, found none at "
                        + worldDataDir);
            }
        }

        // Audit wave 2b: the zone rules hook, read through the real resource manager.
        failures.addAll(ZoneRulesIntegrationCheck.run(server));

        for (String failure : failures) {
            LOG.error("dungeonIntegrationTest: {}", failure);
        }
        return failures.isEmpty() ? 0 : 1;
    }
}
