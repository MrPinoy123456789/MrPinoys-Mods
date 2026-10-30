package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Holds the one live {@link WildState} for the running server (decision 2026-10-30, wild lodestones). Loaded at server
 * start, saved after every change, dropped at stop. The file is small, so every mutation saves.
 *
 * <p>Outside wild mode nothing is loaded and nothing is written.
 */
final class WildStore {

    private static final String DIR = "pocketdungeons-wild";
    private static final String FILE = "lodestones.json";

    private static WildState state;
    private static Path file;

    private WildStore() {}

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(WildStore::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (state != null) {
                save();
            }
            state = null;
            file = null;
        });
    }

    /** The live state, or {@code null} when wild mode is off or no server is running. */
    static WildState state() {
        return state;
    }

    static Path fileFor(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR).resolve(FILE);
    }

    private static void load(MinecraftServer server) {
        if (!WildMode.active()) {
            return;
        }
        file = fileFor(server);
        try {
            state = WildState.load(file);
        } catch (IOException | RuntimeException e) {
            PocketDungeonsMod.LOG.error("Wild lodestone state at {} is unreadable; starting empty", file, e);
            quarantine(file);
            state = new WildState();
        }
        // The live dungeons were memory only, so after a start every lodestone is ready again.
        for (WildState.Lodestone lodestone : state.all()) {
            if (lodestone.phase != WildState.Phase.READY) {
                lodestone.markReady();
            }
        }
        save();
    }

    /** Writes the state now. Call after every mutation. */
    static void save() {
        if (state == null || file == null) {
            return;
        }
        try {
            state.save(file);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not save wild lodestone state to {}", file, e);
        }
    }

    private static void quarantine(Path bad) {
        try {
            if (Files.exists(bad)) {
                Files.move(bad, bad.resolveSibling(bad.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not move the bad wild state file aside", e);
        }
    }
}
