package pocketdungeons;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code dungeonLoadTest} entrypoint: a repeatable, headless load test that
 * drives concurrent dungeon instances through the real dedicated server route
 * (the same one {@code dungeonIntegrationTest} proves loads) and reports the
 * operating-envelope measurements M76 publishes.
 *
 * <p>This is a measurement harness, not a benchmark that promises capacity. The
 * constraints in M76's handoff are explicit: never improve the numbers by
 * disabling protection, persistence, GameTests or resource floors; all world
 * writes stay on the server thread; these are measurement points, not a
 * capacity claim that holds on every mixed-mod server. The numbers this prints
 * are what one declared JVM and world actually sustained, and the
 * {@code /dungeon admin diagnostics} command is what an operator repeats them
 * with on their own hardware.
 *
 * <p>Why {@code adminBuild} and not a real {@code /dungeon}: a real entry goes
 * through a player, a keystone, a lobby and a door choice, none of which a
 * headless server has. {@link Instances#adminBuild} plans and stamps the same
 * procedural layout the real path stamps, against the same room manifest and
 * the same force-load and bedrock-envelope code, so the world work it measures
 * is the production work. It deliberately bypasses the M76 instance cap: the
 * load test measures raw headroom, not the cap's refusal behaviour, which the
 * cap unit checks cover.
 *
 * <p>Profiles are system properties, so the same task runs a small smoke load
 * or a large one without a code change:
 * <ul>
 *   <li>{@code pocketdungeons.loadtest.instances}: concurrent instances to hold
 *       (default 8).</li>
 *   <li>{@code pocketdungeons.loadtest.cycles}: build/hold/clear cycles to
 *       repeat (default 1).</li>
 *   <li>{@code pocketdungeons.loadtest.steadyTicks}: ticks to hold the full
 *       load before clearing, the window tick duration is sampled over
 *       (default 200).</li>
 *   <li>{@code pocketdungeons.loadtest.soak}: {@code true} widens every
 *       default for a long soak invocation (the distinct
 *       {@code dungeonLoadTestSoak} task sets this).</li>
 * </ul>
 *
 * <p>Inert everywhere else: it only acts when {@code pocketdungeons.loadtest}
 * is {@code true}, which only the load test Gradle task's JVM sets.
 */
public final class DungeonLoadTestEntrypoint implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("PocketDungeonsLoadTest");
    private static final String ENABLE_PROPERTY = "pocketdungeons.loadtest";
    private static final String SOAK_PROPERTY = "pocketdungeons.loadtest.soak";

    /** Bounds how long this waits for SERVER_STARTED before failing loud. */
    private static final long STARTUP_TIMEOUT_SECONDS = 180;

    @Override
    public void onInitialize() {
        if (!"true".equals(System.getProperty(ENABLE_PROPERTY))) {
            return;
        }

        boolean soak = "true".equals(System.getProperty(SOAK_PROPERTY));
        int instances = intProp("pocketdungeons.loadtest.instances", soak ? 32 : 8);
        int cycles = intProp("pocketdungeons.loadtest.cycles", soak ? 20 : 1);
        int steadyTicks = intProp("pocketdungeons.loadtest.steadyTicks", soak ? 1000 : 200);

        AtomicBoolean started = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_TIMEOUT_SECONDS * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            if (!started.get()) {
                LOG.error("dungeonLoadTest: server did not reach SERVER_STARTED within {}s",
                        STARTUP_TIMEOUT_SECONDS);
                System.exit(1);
            }
        }, "dungeon-load-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            started.set(true);
            watchdog.interrupt();
            LoadTestController controller = new LoadTestController(server, instances, cycles, steadyTicks);
            controller.start();
        });
    }

    private static int intProp(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * The tick-driven state machine. Each phase advances on END_SERVER_TICK so
     * the server's own tick loop runs between steps: the tick-spread clears
     * actually progress, and tick duration is sampled against a live tick
     * loop rather than a tight loop that starves it.
     */
    private static final class LoadTestController {
        private final MinecraftServer server;
        private final int targetInstances;
        private final int cycles;
        private final int steadyTicks;

        private Phase phase = Phase.INIT;
        private final List<Integer> liveSlots = new ArrayList<>();
        private int built;
        private int cycleIndex;
        private int steadyCount;

        // Measurements.
        private final List<Long> generationNanos = new ArrayList<>();
        private final List<Long> clearMillis = new ArrayList<>();
        private final List<Long> steadyTickNanos = new ArrayList<>();
        private long tickStartNanos;
        private long purgeStartMillis;
        private long cycleStartMillis;

        private static final class Phase {
            static final Phase INIT = new Phase("init");
            static final Phase BUILD = new Phase("build");
            static final Phase STEADY = new Phase("steady");
            static final Phase PURGE = new Phase("purge");
            static final Phase CLEAR = new Phase("clear");
            static final Phase REPORT = new Phase("report");
            static final Phase DONE = new Phase("done");
            final String name;
            private Phase(String name) { this.name = name; }
        }

        LoadTestController(MinecraftServer server, int targetInstances, int cycles, int steadyTicks) {
            this.server = server;
            this.targetInstances = targetInstances;
            this.cycles = cycles;
            this.steadyTicks = steadyTicks;
        }

        void start() {
            // Sample tick start/end so STEADY sees real tick work duration.
            ServerTickEvents.START_SERVER_TICK.register(s -> {
                if (phase == Phase.STEADY) {
                    tickStartNanos = System.nanoTime();
                }
            });
            ServerTickEvents.END_SERVER_TICK.register(s -> onEndTick());
            LOG.info("dungeonLoadTest: starting (instances={}, cycles={}, steadyTicks={})",
                    targetInstances, cycles, steadyTicks);
        }

        private void onEndTick() {
            switch (phase.name) {
                case "init" -> {
                    cycleStartMillis = System.currentTimeMillis();
                    liveSlots.clear();
                    built = 0;
                    phase = Phase.BUILD;
                }
                case "build" -> buildOne();
                case "steady" -> steadyOne();
                case "purge" -> purgeAll();
                case "clear" -> clearWait();
                case "report" -> reportAndExit();
                default -> { /* done: idle */ }
            }
        }

        private void buildOne() {
            long seed = server.overworld().getRandom().nextLong();
            long t0 = System.nanoTime();
            int slot = Instances.adminBuild(server, seed, 1, false);
            long elapsed = System.nanoTime() - t0;
            if (slot >= 0) {
                liveSlots.add(slot);
                generationNanos.add(elapsed);
            } else {
                LOG.warn("dungeonLoadTest: adminBuild returned {} at cycle {} build {}",
                        slot, cycleIndex, built);
            }
            if (++built >= targetInstances) {
                steadyCount = 0;
                steadyTickNanos.clear();
                phase = Phase.STEADY;
            }
        }

        private void steadyOne() {
            if (tickStartNanos > 0) {
                steadyTickNanos.add(System.nanoTime() - tickStartNanos);
            }
            if (++steadyCount >= steadyTicks) {
                sampleFootprint();
                phase = Phase.PURGE;
            }
        }

        private void purgeAll() {
            purgeStartMillis = System.currentTimeMillis();
            for (int slot : new ArrayList<>(liveSlots)) {
                Instances.adminPurge(server, slot);
            }
            phase = Phase.CLEAR;
        }

        private void clearWait() {
            // A clear releases its slot from usedSlots when its tick-spread
            // block write finishes, so this waits until every slot this
            // cycle opened is back on the free list.
            boolean allReleased = true;
            for (int slot : liveSlots) {
                if (InstanceRegistry.usedSlots.contains(slot)) {
                    allReleased = false;
                    break;
                }
            }
            if (allReleased) {
                clearMillis.add(System.currentTimeMillis() - purgeStartMillis);
                LOG.info("dungeonLoadTest: cycle {}/{} done ({} instances cleared in {} ms)",
                        cycleIndex + 1, cycles, liveSlots.size(),
                        clearMillis.get(clearMillis.size() - 1));
                liveSlots.clear();
                if (++cycleIndex >= cycles) {
                    phase = Phase.REPORT;
                } else {
                    cycleStartMillis = System.currentTimeMillis();
                    built = 0;
                    phase = Phase.BUILD;
                }
            }
        }

        private void sampleFootprint() {
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            int forced = level != null
                    ? level.getChunkSource().getForceLoadedChunks().size() : -1;
            Runtime rt = Runtime.getRuntime();
            long usedHeap = rt.totalMemory() - rt.freeMemory();
            LOG.info("dungeonLoadTest: steady footprint at {} instances: heap={} MB, forced chunks={}",
                    liveSlots.size(), usedHeap / 1024 / 1024, forced);
        }

        private void reportAndExit() {
            phase = Phase.DONE;
            long totalSeconds = (System.currentTimeMillis() - cycleStartMillis) / 1000;
            LOG.info("dungeonLoadTest: === SUMMARY ===");
            LOG.info("dungeonLoadTest: instances per cycle: {}", targetInstances);
            LOG.info("dungeonLoadTest: cycles: {}", cycles);
            LOG.info("dungeonLoadTest: total wall time: {} s", totalSeconds);
            LOG.info("dungeonLoadTest: generation latency (ms): {}",
                    nanosToMsSummary(generationNanos));
            LOG.info("dungeonLoadTest: clear latency (ms): {}", msSummary(clearMillis));
            LOG.info("dungeonLoadTest: steady tick duration (ms): {}",
                    nanosToMsSummary(steadyTickNanos));
            Runtime rt = Runtime.getRuntime();
            LOG.info("dungeonLoadTest: final heap: {} MB / {} MB max",
                    (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
                    rt.maxMemory() / 1024 / 1024);
            int exitCode = generationNanos.isEmpty() ? 1 : 0;
            LOG.info("dungeonLoadTest: {}", exitCode == 0 ? "PASS" : "FAIL");
            // Exit from a separate thread so the server thread can unwind its
            // tick loop before the JVM shutdown hook resolves; same reasoning
            // as DungeonIntegrationEntrypoint.
            Thread exit = new Thread(() -> System.exit(exitCode), "dungeon-load-test-exit");
            exit.setDaemon(true);
            exit.start();
        }

        private static String nanosToMsSummary(List<Long> nanos) {
            if (nanos.isEmpty()) {
                return "no samples";
            }
            long[] ms = new long[nanos.size()];
            for (int i = 0; i < nanos.size(); i++) {
                ms[i] = Math.round(nanos.get(i) / 1_000_000.0);
            }
            return percentileSummary(ms);
        }

        private static String msSummary(List<Long> millis) {
            if (millis.isEmpty()) {
                return "no samples";
            }
            long[] ms = new long[millis.size()];
            for (int i = 0; i < millis.size(); i++) {
                ms[i] = millis.get(i);
            }
            return percentileSummary(ms);
        }

        private static String percentileSummary(long[] values) {
            Arrays.sort(values);
            long p50 = values[values.length / 2];
            long p95 = values[(int) Math.ceil(values.length * 0.95) - 1];
            long p99 = values[(int) Math.ceil(values.length * 0.99) - 1];
            return "p50=" + p50 + ", p95=" + p95 + ", p99=" + p99
                    + ", min=" + values[0] + ", max=" + values[values.length - 1]
                    + ", n=" + values.length;
        }
    }
}
