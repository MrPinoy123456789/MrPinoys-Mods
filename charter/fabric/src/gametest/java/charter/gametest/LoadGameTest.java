package charter.gametest;

import charter.config.Knobs;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.LoadMeter;
import charter.mc.scenario.ScenarioCommands;
import charter.mc.scenario.ScenarioFiles;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * M14: the dev server half of the load check (PLAN.md risk R1). An encirclement
 * is built with the scenario command, twenty mock players walk about it for
 * three hundred ticks (three sim steps at the default {@code simStepTicks}), and Charter's own tick work is timed: the sim step and every
 * player's crossing check. The numbers are written to {@code charter-load.txt} in
 * the run folder and recorded in README.md; the test fails only if either takes
 * more than a tenth of a 50 ms tick on average.
 */
public final class LoadGameTest {

    private static final int PLAYERS = 20;
    private static final int TICKS = 300;
    private static final int CHUNK_X = 13_500;
    private static final int CHUNK_Z = 12_000;

    @GameTest(maxTicks = 400, environment = "charter-gametest:long_siege")
    public void twentyPlayersAroundASiegeCostLittle(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = server.overworld();
        CharterRuntime rt = CharterServer.runtime();
        Map<Field, Object> knobs = new HashMap<>();
        for (Field f : Knobs.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) knobs.put(f, f.get(rt.knobs));
        }
        for (String name : ScenarioFiles.DEFAULTS) Files.deleteIfExists(ScenarioCommands.dir().resolve(name + ".json"));
        int cx = CHUNK_X * 16 + 8;
        int cz = CHUNK_Z * 16 + 8;
        for (int x = CHUNK_X - 3; x <= CHUNK_X + 3; x++) {
            for (int z = CHUNK_Z - 3; z <= CHUNK_Z + 3; z++) level.setChunkForced(x, z, true);
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "charter admin scenario runat " + cx + " " + cz + " encirclement");
        // The scenario's knobs go back at once: tests beside this one run while it measures,
        // and the measurement is of the shipped settings anyway.
        restore(rt, knobs);

        Random random = new Random(14);
        List<ServerPlayer> walkers = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            int x = cx + random.nextInt(80) - 40;
            int z = cz + random.nextInt(80) - 40;
            p.teleportTo(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
            walkers.add(p);
        }
        LoadMeter.reset();
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (ticks[0]++ >= TICKS) return;
            for (ServerPlayer p : walkers) {
                int x = (int) Math.floor(p.getX()) + random.nextInt(7) - 3;
                int z = (int) Math.floor(p.getZ()) + random.nextInt(7) - 3;
                x = Math.max(cx - 48, Math.min(cx + 48, x));
                z = Math.max(cz - 48, Math.min(cz + 48, z));
                p.teleportTo(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
            }
        });
        helper.runAfterDelay(TICKS + 5, () -> {
            double step = LoadMeter.stepMillis();
            double crossings = LoadMeter.crossingMillis();
            double serverTick = server.getAverageTickTimeNanos() / 1e6;
            String result = String.format(Locale.ROOT,
                    "dev server load: %d mock players over %d ticks beside an encirclement; Charter sim step mean %.3f ms over %d steps, crossing checks mean %.3f ms a tick, whole server tick mean %.2f ms",
                    PLAYERS, TICKS, step, LoadMeter.steps(), crossings, serverTick);
            try {
                Files.writeString(FabricLoader.getInstance().getGameDir().resolve("charter-load.txt"), result + System.lineSeparator());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            CharterServerHooks.log(result);
            for (ServerPlayer p : walkers) server.getPlayerList().remove(p);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "charter admin scenario clear");
            for (int x = CHUNK_X - 3; x <= CHUNK_X + 3; x++) {
                for (int z = CHUNK_Z - 3; z <= CHUNK_Z + 3; z++) level.setChunkForced(x, z, false);
            }
            helper.assertTrue(LoadMeter.steps() > 0, "The sim never stepped");
            helper.assertTrue(step < 5, "The sim step took " + step + " ms on average");
            helper.assertTrue(crossings < 5, "Crossing checks took " + crossings + " ms a tick on average");
            helper.succeed();
        });
    }

    private static void restore(CharterRuntime rt, Map<Field, Object> knobs) {
        for (Map.Entry<Field, Object> e : knobs.entrySet()) {
            try {
                e.getKey().set(rt.knobs, e.getValue());
            } catch (IllegalAccessException bad) {
                throw new IllegalStateException(bad);
            }
        }
    }

    /** The log, kept out of the test body so the result line stands on its own in the server log. */
    private static final class CharterServerHooks {
        static void log(String line) {
            charter.mc.CharterMod.LOG.info(line);
        }
    }
}
