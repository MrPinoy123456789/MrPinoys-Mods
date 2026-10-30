package foundry.mc;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackStatusConsumer;
import foundry.core.PackInputs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runtime pack builds for {@code /foundry pack build}. The build runs on a daemon worker thread, never the server
 * thread, into a sibling temp file that replaces the main zip only on success: a failed build leaves the last good
 * zip in place. A build is skipped when {@link PackInputs} says the inputs did not change.
 */
final class PackBuilds {
    private static final AtomicBoolean BUILDING = new AtomicBoolean();
    private static volatile String baselineHash;
    private static volatile String lastInputHash;
    private static volatile long lastSuccessMs;
    private static volatile String lastFailure;

    private PackBuilds() {}

    /** The digest of every file that feeds the pack (content definitions plus authored assets). */
    static String inputHash() {
        return PackInputs.digest(FoundryMod.contentRoot(), FoundryMod.assetsRoot());
    }

    /** Called at startup so the first {@code pack build} knows whether anything changed since. */
    static void markBaseline() {
        baselineHash = inputHash();
    }

    static boolean isBuilding() {
        return BUILDING.get();
    }

    static long lastSuccessMs() {
        return lastSuccessMs;
    }

    static String lastFailure() {
        return lastFailure;
    }

    /** True when the files on disk differ from the ones the last build (or the startup build) used. */
    static boolean inputsChanged() {
        String hash = inputHash();
        String base = lastInputHash != null ? lastInputHash : baselineHash;
        return base != null && !base.equals(hash);
    }

    /**
     * Starts a build unless inputs are unchanged; replies through {@code reply} on the server thread, since it is
     * called back via {@code server.execute}.
     */
    static void build(net.minecraft.server.MinecraftServer server, Consumer<String> reply) {
        if (BUILDING.get()) {
            reply.accept("Foundry: a pack build is already running.");
            return;
        }
        String hash = inputHash();
        if (lastInputHash != null && lastInputHash.equals(hash) && lastFailure == null) {
            reply.accept("Foundry: inputs unchanged since the last build; nothing to do.");
            return;
        }
        if (lastInputHash == null && baselineHash != null && baselineHash.equals(hash)) {
            lastInputHash = hash;
            reply.accept("Foundry: inputs unchanged since the startup build; nothing to do.");
            return;
        }
        Path main = PolymerResourcePackUtils.getMainPath();
        if (main == null) {
            reply.accept("Foundry: Polymer has no pack path; cannot build.");
            return;
        }
        BUILDING.set(true);
        Thread worker = new Thread(() -> {
            try {
                Path tmp = main.resolveSibling(main.getFileName() + ".foundry-tmp");
                var result = PolymerResourcePackUtils.getInstance().build(tmp,
                        ResourcePackStatusConsumer.simple(s -> FoundryMod.LOG.info("pack build: {}", s)));
                if (result.hadIssues()) {
                    Files.deleteIfExists(tmp);
                    lastFailure = "the build reported issues; see the server log";
                    server.execute(() -> reply.accept("Foundry: pack build had issues; the previous pack is still live. "
                            + "See the server log."));
                } else {
                    try {
                        Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING);
                    }
                    lastInputHash = hash;
                    lastSuccessMs = System.currentTimeMillis();
                    lastFailure = null;
                    server.execute(() -> reply.accept("Foundry: pack built (" + result.hash()
                            + "). Online players need /polymer generate-pack reload or a rejoin to fetch it."));
                }
            } catch (Exception e) {
                lastFailure = e.toString();
                FoundryMod.LOG.warn("Foundry: pack build failed: {}", e.toString());
                server.execute(() -> reply.accept("Foundry: pack build failed: " + e.getMessage()
                        + ". The previous pack is still live."));
            } finally {
                BUILDING.set(false);
            }
        }, "foundry-pack-build");
        worker.setDaemon(true);
        reply.accept("Foundry: pack build started on a worker thread.");
        worker.start();
    }
}
