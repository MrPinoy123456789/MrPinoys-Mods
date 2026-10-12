package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Roadmap T33: the Frostworks finale script driven through {@link FinaleWave#step} as the finale-script skill
 * describes. Pins the brazier scan (lit campfires only), the tell before the cold, the freeze rate, the thaw
 * beside a brazier, the health floor, the cold stopping on a win and on the floor ending, and no cold at all
 * for a room without a brazier. The cold is advanced by calling {@code FrostFinale.coldTick} directly, since the
 * gametest world has no dungeon dimension for the tick listener.
 */
public final class FrostFinaleGameTest {

    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:zombie", 1)), 0, null);

    /** Takes over the spawn so the wave is one zombie; everything else is the real script. */
    private static final class Wrapped implements FinaleScript {
        final FrostFinale inner = new FrostFinale();

        @Override
        public boolean spawnWave(FinaleContext ctx) {
            ctx.spawnNearParty("minecraft:zombie");
            return true;
        }

        @Override
        public void onWaveSpawned(FinaleContext ctx) {
            inner.onWaveSpawned(ctx);
        }

        @Override
        public void onWon(FinaleContext ctx) {
            inner.onWon(ctx);
        }

        @Override
        public void onFloorEnded(FinaleContext ctx) {
            inner.onFloorEnded(ctx);
        }
    }

    @GameTest(maxTicks = 60)
    public void frostworksHasAScriptAndTheScanFindsLitCampfiresOnly(GameTestHelper helper) {
        helper.assertTrue(FinaleScripts.forDungeon("pocketdungeons:frostworks") instanceof FrostFinale,
                "the Frostworks dungeon has a finale script");
        Run run = new Run(helper, 9941);
        try {
            run.campfire(10, 2, 10, true, false);
            run.campfire(11, 2, 10, false, false);
            run.campfire(12, 2, 10, true, true);
            run.level.setBlock(run.abs(5, 2, 5), Blocks.STONE.defaultBlockState(), 3);
            List<BlockPos> found = FrostFinale.scanBraziers(run.level, FrostFinale.roomBoxes(run.record));
            helper.assertValueEqual(found.size(), 2, "a lit campfire and a lit soul campfire count; an unlit one does not");
            helper.assertTrue(found.contains(run.abs(10, 2, 10)) && found.contains(run.abs(12, 2, 10)),
                    "the lit ones are the ones found");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void theColdWaitsForTheTellThenFreezesAtTheRate(GameTestHelper helper) {
        Run run = new Run(helper, 9942);
        try {
            run.campfire(10, 2, 10, true, false);
            run.start();
            FrostFinale.Run state = run.state();
            helper.assertTrue(FrostFinale.coldRunning(run.record.slot), "a cold is running");
            helper.assertValueEqual(state.braziers.size(), 1, "the brazier was found by scanning");
            helper.assertTrue(state.coldAt - run.server.getTickCount() >= FrostFinaleRules.MIN_TELL_TICKS,
                    "the cold waits at least 2 seconds");
            long coldAt = state.coldAt;
            for (long now = coldAt - 40; now < coldAt; now++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), now);
            }
            helper.assertValueEqual(run.member.getTicksFrozen(), 0, "nothing freezes during the tell");
            for (int n = 0; n < 100; n++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), coldAt + n);
            }
            int frozen = run.member.getTicksFrozen();
            int expected = (int) (100 * FrostFinaleRules.risePerTick(PocketDungeonsConfig.frostColdTicksPerSecond()));
            helper.assertTrue(Math.abs(frozen - expected) <= 1, "100 ticks of cold add the knob's rate: " + frozen);
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void standingByABrazierThaws(GameTestHelper helper) {
        Run run = new Run(helper, 9943);
        try {
            run.campfire(10, 2, 10, true, false);
            run.start();
            FrostFinale.Run state = run.state();
            long now = state.coldAt;
            for (int n = 0; n < 300; n++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), now++);
            }
            helper.assertTrue(run.member.getTicksFrozen() > 50, "frozen away from the fire");
            run.moveMember(9, 2, 10);
            for (int n = 0; n < 10; n++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), now++);
            }
            helper.assertValueEqual(run.member.getTicksFrozen(), 0, "a few blocks from a lit brazier thaws fast");
            run.level.setBlock(run.abs(10, 2, 10), Blocks.CAMPFIRE.defaultBlockState()
                    .setValue(CampfireBlock.LIT, false), 3);
            for (int n = 0; n < 100; n++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), now++);
            }
            helper.assertTrue(run.member.getTicksFrozen() > 0, "a brazier put out no longer warms");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void theColdIsNeverLethalAtOneHeart(GameTestHelper helper) {
        Run run = new Run(helper, 9944);
        try {
            run.campfire(10, 2, 10, true, false);
            run.start();
            FrostFinale.Run state = run.state();
            run.member.setHealth(2.0f);
            run.member.setTicksFrozen(FrostFinaleRules.FULL_CEILING_TICKS);
            long now = state.coldAt;
            for (int n = 0; n < 2000; n++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), now++);
                helper.assertTrue(run.member.getTicksFrozen() < FrostFinaleRules.FULL_FREEZE_TICKS - 2,
                        "a member at one heart is held under the freeze line: " + run.member.getTicksFrozen());
            }
            run.member.setHealth(20.0f);
            for (int n = 0; n < 2000; n++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), now++);
            }
            helper.assertTrue(run.member.getTicksFrozen() - FrostFinaleRules.VANILLA_DECAY_PER_TICK
                    >= FrostFinaleRules.FULL_FREEZE_TICKS, "a healthy member does reach a full freeze");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void winningStopsTheColdAtOnce(GameTestHelper helper) {
        Run run = new Run(helper, 9945);
        try {
            run.campfire(10, 2, 10, true, false);
            run.start();
            FrostFinale.Run state = run.state();
            long now = state.coldAt;
            for (int n = 0; n < 200; n++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), now++);
            }
            helper.assertTrue(run.member.getTicksFrozen() > 0, "frozen before the win");
            for (UUIDHolder id : UUIDHolder.of(FinaleWave.stateOf(run.record).mobs)) {
                id.discard(run.level);
            }
            run.step();
            helper.assertTrue(FinaleWave.stateOf(run.record).done, "the finale is won");
            helper.assertValueEqual(run.member.getTicksFrozen(), 0, "the member is thawed on the win");
            helper.assertTrue(!FrostFinale.coldRunning(run.record.slot), "no cold runs after the win");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void theFloorEndingStopsTheCold(GameTestHelper helper) {
        Run run = new Run(helper, 9946);
        try {
            run.campfire(10, 2, 10, true, false);
            run.start();
            FrostFinale.Run state = run.state();
            long now = state.coldAt;
            for (int n = 0; n < 200; n++) {
                FrostFinale.coldTick(run.level, state, List.of(run.member), now++);
            }
            helper.assertTrue(run.member.getTicksFrozen() > 0, "frozen before the floor ends");
            FinaleWave.floorEnded(run.level, run.record);
            helper.assertValueEqual(run.member.getTicksFrozen(), 0, "the floor ending thaws the member");
            helper.assertTrue(!FrostFinale.coldRunning(run.record.slot), "no cold runs after the floor ends");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void aRoomWithoutABrazierGetsNoCold(GameTestHelper helper) {
        Run run = new Run(helper, 9947);
        try {
            run.start();
            helper.assertTrue(!FrostFinale.coldRunning(run.record.slot), "no braziers, no cold");
            helper.assertValueEqual(run.state().coldAt, -1L, "no cold is scheduled");
            FrostFinale.coldTick(run.level, run.state(), List.of(run.member), 1_000_000L);
            helper.assertValueEqual(run.member.getTicksFrozen(), 0, "nobody freezes");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    /** Small helper so a test can discard the wave's mobs by id. */
    private record UUIDHolder(java.util.UUID id) {
        static List<UUIDHolder> of(List<java.util.UUID> ids) {
            return ids.stream().map(UUIDHolder::new).toList();
        }

        void discard(ServerLevel level) {
            net.minecraft.world.entity.Entity entity = level.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        }
    }

    /** One scripted finale on a hand built record with one mock member. */
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;
        final String dungeonId;

        Run(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.dungeonId = "pocketdungeons:test_frostworks_" + slot;
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            player.setInvulnerable(true);
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
            FinaleScripts.register(dungeonId, new Wrapped());
            moveMember(1, 2, 1);
        }

        BlockPos abs(int x, int y, int z) {
            return helper.absolutePos(new BlockPos(x, y, z));
        }

        void moveMember(int x, int y, int z) {
            BlockPos where = abs(x, y, z);
            member.teleportTo(level, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, Set.of(), 0.0F, 0.0F,
                    false);
        }

        void campfire(int x, int y, int z, boolean lit, boolean soul) {
            level.setBlock(abs(x, y, z), (soul ? Blocks.SOUL_CAMPFIRE : Blocks.CAMPFIRE).defaultBlockState()
                    .setValue(CampfireBlock.LIT, lit), 3);
        }

        void step() {
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Freeze");
        }

        /** Triggers, skips the countdown and lets the wave spawn (which starts the tell). */
        void start() {
            FinaleWave.step(server, level, record, FINALE, List.of(member), true, "Test Freeze");
            FinaleWave.stateOf(record).spawnTick = 0;
            step();
            helper.assertValueEqual(FinaleWave.stateOf(record).mobs.size(), 1, "the wave stands");
        }

        FrostFinale.Run state() {
            return (FrostFinale.Run) FinaleWave.stateOf(record).scriptState.get(FrostFinale.Run.class);
        }

        void close() {
            FinaleWave.teardown(level, record.slot);
            FinaleScripts.unregister(dungeonId);
            FrostFinale.release(server, record.slot, List.of(member));
        }
    }
}
