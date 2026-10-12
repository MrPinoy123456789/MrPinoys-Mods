package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Roadmap T21: the Ossuary coffins finale. Drives {@link FinaleWave#step} with the real {@link OssuaryFinale}
 * over three coffins stamped with the room's own helper. Four cows stand in for the wave, so the coffins hold
 * 2, 1 and 1. Checks the sealed state, the tell before the lid breaks, one coffin at a time, the skip rule, a
 * lid broken by a player, the finale tag (so Restless never raises them), the floor-end restore and the data
 * wave fallback when a room has no coffins.
 */
public final class OssuaryFinaleGameTest {

    private static final String DUNGEON = "pocketdungeons:ossuary";
    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:cow", 4)), 0, null);

    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;
        final BlockPos origin;
        final int slot;

        Run(GameTestHelper helper, int slot, boolean coffins, int memberX, int memberZ) {
            this.helper = helper;
            this.slot = slot;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.origin = helper.absolutePos(BlockPos.ZERO);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            if (coffins) {
                FinalFloorEarlySpecs.coffin(level, origin, 6, 3, 1);
                FinalFloorEarlySpecs.coffin(level, origin, 13, 3, 1);
                FinalFloorEarlySpecs.coffin(level, origin, 13, 12, -1);
            }
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(level, origin.getX() + memberX + 0.5, origin.getY() + 1, origin.getZ() + memberZ + 0.5,
                    Set.of(), 0.0F, 0.0F, false);
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
            InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                    player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = DUNGEON;
            helper.assertTrue(FinaleScripts.forDungeon(DUNGEON) instanceof OssuaryFinale,
                    "the Ossuary finale script is registered");
        }

        void step() {
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Room");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        /** Triggers the finale and spawns the wave. */
        void start() {
            FinaleWave.step(server, level, record, FINALE, List.of(member), true, "Test Room");
            state().spawnTick = 0;
            step();
        }

        Mob mob(int n) {
            Entity entity = level.getEntity(state().mobs.get(n));
            helper.assertTrue(entity instanceof Mob, "mob " + n + " is in the world");
            return (Mob) entity;
        }

        boolean lidIntact(int x, int headZ, int dz) {
            return level.getBlockState(origin.offset(x, 2, headZ + dz)).is(Blocks.POLISHED_BLACKSTONE)
                    && level.getBlockState(origin.offset(x, 2, headZ + 2 * dz)).is(Blocks.POLISHED_BLACKSTONE);
        }

        void close() {
            FinaleWave.teardown(level, slot);
        }
    }

    private static boolean dormant(Mob mob) {
        return mob.isNoAi() && mob.isInvisible() && mob.isInvulnerable();
    }

    @GameTest(maxTicks = 300)
    public void coffinsHoldTheWaveAndOpenWithATell(GameTestHelper helper) {
        Run run = new Run(helper, 9981, true, 8, 4);
        run.start();
        helper.assertValueEqual(run.state().mobs.size(), 4, "the wave is four mobs, all counted toward alive");
        for (int i = 0; i < 4; i++) {
            helper.assertTrue(dormant(run.mob(i)), "mob " + i + " lies dormant in its coffin");
            helper.assertTrue(run.mob(i).entityTags().contains(FinaleWave.TAG),
                    "mob " + i + " carries the finale tag, so Restless never raises it");
        }
        helper.assertValueEqual(FinaleWave.alive(run.level, run.state()), 4, "the pad stays shut: four alive");
        long start = run.server.getTickCount();
        boolean[] sawTell = {false};
        helper.onEachTick(() -> {
            run.step();
            long t = run.server.getTickCount() - start;
            if (t == OssuaryRules.MIN_GAP_TICKS + 20) {
                helper.assertTrue(run.lidIntact(6, 3, 1), "mid tell: the lid still holds");
                helper.assertTrue(dormant(run.mob(0)), "mid tell: occupant 0 still sleeps");
                helper.assertTrue(dormant(run.mob(1)), "mid tell: occupant 1 still sleeps");
                sawTell[0] = true;
            }
            if (t == OssuaryRules.MIN_GAP_TICKS + OssuaryRules.CRACK_TICKS + 10) {
                helper.assertTrue(sawTell[0], "the tell was seen first");
                helper.assertTrue(!run.lidIntact(6, 3, 1), "the lid has broken");
                helper.assertTrue(!dormant(run.mob(0)) && !run.mob(0).isNoAi() && !run.mob(1).isInvisible(),
                        "the first coffin's occupants stand awake");
                helper.assertTrue(dormant(run.mob(2)) && dormant(run.mob(3)),
                        "the other coffins are still shut: one at a time");
                helper.assertTrue(run.lidIntact(13, 3, 1) && run.lidIntact(13, 12, -1), "their lids hold");
                FinaleWave.floorEnded(run.level, run.record);
                helper.assertTrue(run.lidIntact(6, 3, 1), "floor end restores the broken lid");
                run.close();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void aSkippedCoffinOpensAnyway(GameTestHelper helper) {
        Run run = new Run(helper, 9982, true, 1, 14);
        run.start();
        long start = run.server.getTickCount();
        helper.onEachTick(() -> {
            run.step();
            long t = run.server.getTickCount() - start;
            if (t == OssuaryRules.SKIP_DELAY_TICKS - 20) {
                helper.assertTrue(run.lidIntact(6, 3, 1) && run.lidIntact(13, 3, 1) && run.lidIntact(13, 12, -1),
                        "nobody is near and the delay has not passed: all shut");
            }
            if (t == OssuaryRules.SKIP_DELAY_TICKS + OssuaryRules.CRACK_TICKS + 10) {
                int open = 0;
                open += run.lidIntact(6, 3, 1) ? 0 : 1;
                open += run.lidIntact(13, 3, 1) ? 0 : 1;
                open += run.lidIntact(13, 12, -1) ? 0 : 1;
                helper.assertValueEqual(open, 1, "exactly one skipped coffin has opened by itself");
                run.close();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void aLidBrokenByAPlayerFreesTheOccupants(GameTestHelper helper) {
        Run run = new Run(helper, 9983, true, 1, 14);
        try {
            run.start();
            helper.assertTrue(dormant(run.mob(0)), "asleep before the lid goes");
            run.level.setBlock(run.origin.offset(6, 2, 4), Blocks.AIR.defaultBlockState(), 3);
            run.step();
            helper.assertTrue(!dormant(run.mob(0)) && !dormant(run.mob(1)),
                    "a mined lid wakes what lies under it, so nobody is stuck asleep");
            helper.assertTrue(dormant(run.mob(2)), "the other coffins are untouched");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aRoomWithNoCoffinsRunsTheDataWave(GameTestHelper helper) {
        Run run = new Run(helper, 9984, false, 1, 14);
        try {
            run.start();
            helper.assertValueEqual(run.state().mobs.size(), 4, "the data wave stands: four cows");
            helper.assertTrue(!dormant(run.mob(0)), "they are ordinary finale mobs, not sleepers");
        } finally {
            run.close();
        }
        helper.succeed();
    }
}
