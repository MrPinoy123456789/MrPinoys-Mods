package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Roadmap T01: the finale script seam. The gametest world has no dungeon dimension, so {@code FinaleWave.tick}
 * cannot run end to end; these tests drive {@link FinaleWave#step} (the state machine {@code tick} calls) with
 * a hand built record, a mock member and a recording {@link FinaleScript}, and assert the order of the hooks,
 * that an added mob keeps the finale (and so the pad) open, that a script can replace the data wave, that a
 * throwing hook does not stop the finale, and that a dungeon with no script wins exactly as before.
 */
public final class FinaleScriptGameTest {

    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:cow", 4)), 0, null);
    private static final DungeonDef.Elite ELITE = new DungeonDef.Elite("minecraft:cow", "Test Elite", 20, "");

    /** Writes every hook it receives into {@link #log}; optionally adds an extra mob or replaces the wave. */
    private static class Recorder implements FinaleScript {
        final List<String> log = new ArrayList<>();
        boolean addExtraWhenSpawned;
        boolean replaceWave;

        @Override
        public void onTriggered(FinaleContext ctx) {
            log.add("triggered");
        }

        @Override
        public boolean spawnWave(FinaleContext ctx) {
            log.add("spawnWave");
            if (replaceWave) {
                ctx.spawnNearParty("minecraft:cow");
                ctx.spawnNearParty("minecraft:cow");
                ctx.spawnElite(ELITE);
            }
            return replaceWave;
        }

        @Override
        public void onWaveSpawned(FinaleContext ctx) {
            log.add("waveSpawned:" + ctx.total());
            if (addExtraWhenSpawned) {
                ctx.spawnNearParty("minecraft:cow");
            }
        }

        @Override
        public void onTick(FinaleContext ctx) {
            log.add("tick");
        }

        @Override
        public void onMobDied(FinaleContext ctx, UUID mob) {
            log.add("died");
        }

        @Override
        public void onHalfDead(FinaleContext ctx) {
            log.add("half");
        }

        @Override
        public void onWon(FinaleContext ctx) {
            log.add("won");
        }

        @Override
        public void onFloorEnded(FinaleContext ctx) {
            log.add("floorEnded");
        }
    }

    @GameTest(maxTicks = 40)
    public void everyHookFiresInOrder(GameTestHelper helper) {
        Recorder script = new Recorder();
        Run run = new Run(helper, 9971, "pocketdungeons:test_finale_order", script);
        try {
            run.step(false);
            helper.assertValueEqual(script.log, List.of(), "nothing happens before the floor is cleared");
            run.step(true);
            run.skipCountdown();
            run.step(false);
            run.step(false);
            helper.assertValueEqual(run.state().mobs.size(), 4, "the data wave stands: four cows");
            run.kill(0);
            run.step(false);
            run.kill(1);
            run.step(false);
            run.step(false);
            run.kill(2);
            run.step(false);
            helper.assertTrue(!run.state().done, "one cow is left: the finale is not won");
            run.kill(3);
            run.step(false);
            helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the last death wins the finale");
            FinaleWave.floorEnded(run.level, run.record);
            helper.assertValueEqual(script.log, List.of(
                    "triggered", "spawnWave", "waveSpawned:4",
                    "tick",
                    "died", "tick",
                    "died", "half", "tick",
                    "tick",
                    "died", "tick",
                    "died", "won",
                    "floorEnded"), "the hooks fire in order, onHalfDead once");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void anExtraMobKeepsThePadShut(GameTestHelper helper) {
        Recorder script = new Recorder();
        script.addExtraWhenSpawned = true;
        Run run = new Run(helper, 9972, "pocketdungeons:test_finale_extra", script);
        try {
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertValueEqual(run.state().mobs.size(), 5, "four cows and one extra count toward the total");
            for (int i = 0; i < 4; i++) {
                run.kill(i);
            }
            run.step(false);
            helper.assertValueEqual(FinaleWave.alive(run.level, run.state()), 1, "the extra is the one left alive");
            helper.assertTrue(!run.state().done && !run.record.floor.finaleWon,
                    "the data wave is dead but the extra mob keeps the finale, and so the pad, shut");
            run.kill(4);
            run.step(false);
            helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the extra's death wins it");
            helper.assertTrue(script.log.contains("half"), "half was counted against the grown total");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aScriptCanReplaceTheDataWave(GameTestHelper helper) {
        Recorder script = new Recorder();
        script.replaceWave = true;
        Run run = new Run(helper, 9973, "pocketdungeons:test_finale_replace", script);
        try {
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertValueEqual(run.state().mobs.size(), 3,
                    "two scripted cows and the elite, not the data wave's four");
            helper.assertTrue(run.state().bar != null && run.state().elite != null, "the elite has its boss bar");
            helper.assertTrue(script.log.contains("waveSpawned:3"), "onWaveSpawned still fires after a takeover");
            for (int i = 0; i < 3; i++) {
                run.kill(i);
            }
            run.step(false);
            helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the replaced wave ends the finale");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aThrowingHookDoesNotStopTheFinale(GameTestHelper helper) {
        Recorder script = new Recorder() {
            @Override
            public void onTick(FinaleContext ctx) {
                throw new IllegalStateException("a deliberately broken script");
            }
        };
        Run run = new Run(helper, 9974, "pocketdungeons:test_finale_throws", script);
        try {
            run.step(true);
            run.skipCountdown();
            run.step(false);
            run.step(false);
            for (int i = 0; i < 4; i++) {
                run.kill(i);
            }
            run.step(false);
            helper.assertTrue(run.state().done, "a script that throws cannot keep the pad shut");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aDungeonWithNoScriptRunsTheDataWave(GameTestHelper helper) {
        Run run = new Run(helper, 9975, "pocketdungeons:test_finale_none", null);
        try {
            helper.assertTrue(run.record.interval.dungeonId.equals("pocketdungeons:test_finale_none")
                    && !FinaleScripts.has("pocketdungeons:test_finale_none"), "no script is registered");
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertTrue(run.state().script == FinaleScript.NONE, "the no-op script is used");
            helper.assertValueEqual(run.state().mobs.size(), 4, "the data wave stands");
            for (int i = 0; i < 4; i++) {
                run.kill(i);
            }
            run.step(false);
            helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the wave is won as it always was");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    /** One scripted finale on a hand built record with one mock member. */
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;
        final String dungeonId;
        final boolean scripted;

        Run(GameTestHelper helper, int slot, String dungeonId, FinaleScript script) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.dungeonId = dungeonId;
            this.scripted = script != null;
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
            player.teleportTo(level, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, Set.of(), 0.0F, 0.0F,
                    false);
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
            InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                    player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = dungeonId;
            if (scripted) {
                FinaleScripts.register(dungeonId, script);
            }
        }

        /** One watch tick; {@code due} says the floor's spawners are cleared. */
        void step(boolean due) {
            FinaleWave.step(server, level, record, FINALE, List.of(member), due, "Test Room");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        /** Skips the few seconds between the title and the wave. */
        void skipCountdown() {
            state().spawnTick = 0;
        }

        /** Removes the n-th mob of the finale, as a kill that leaves no body does. */
        void kill(int n) {
            Entity entity = level.getEntity(state().mobs.get(n));
            helper.assertTrue(entity != null, "mob " + n + " is in the world");
            entity.discard();
        }

        void close() {
            FinaleWave.teardown(level, record.slot);
            if (scripted) {
                FinaleScripts.unregister(dungeonId);
            }
        }
    }
}
