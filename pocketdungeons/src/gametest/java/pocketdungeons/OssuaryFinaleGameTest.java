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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Roadmap T21 and T03: the Ossuary coffins finale on the phased spine. Drives {@link FinaleWave#step} with the
 * real {@link OssuaryFinale} over three coffins stamped with the room's own helper. Four cows stand in for the
 * wave: one is the Sexton, the other three are dealt to the coffins. Checks the sealed state in phase one, the
 * tell before the lid breaks in phase two, the skip rule only in phase three, a lid broken by a player, the
 * finale tag (so Restless never raises them), the floor-end restore, a Sexton lost outside the spine, and the
 * data wave fallback when a room has no coffins.
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
            pin();
            sexton = state().elite == null ? null : (Mob) level.getEntity(state().elite);
        }

        /** The Sexton as first stood, kept so a failure can say how it went. */
        Mob sexton;

        String sextonFate() {
            return sexton == null ? "none" : "removed=" + sexton.getRemovalReason() + " health=" + sexton.getHealth()
                    + " last=" + sexton.getLastDamageSource() + " pos=" + sexton.blockPosition();
        }

        /**
         * Holds the Sexton (a cow here) where it stands: no AI, no gravity. In a full run a wandering cow can leave the
         * 16 by 16 slab and fall out of the world, and a Sexton gone any other way wakes every coffin by design, which
         * would open one "by itself" before the phase that allows it.
         */
        void pin() {
            if (state().elite != null && level.getEntity(state().elite) instanceof Mob b && b.isAlive()) {
                b.setNoAi(true);
                b.setNoGravity(true);
                b.setInvulnerable(true); // nothing in a shared full run may kill it between the test's own blows
            }
        }

        BossFight fight() {
            return (BossFight) state().scriptState.get(BossFight.class);
        }

        Mob boss() {
            Mob mob = state().elite == null ? null : (Mob) level.getEntity(state().elite);
            helper.assertTrue(mob != null, "the Sexton stands");
            return mob;
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

    /** A killing blow on the Sexton: the clamp holds him at the next bound, or lets the last phase die. */
    private static void burst(Run run) {
        Mob boss = run.boss();
        boss.setInvulnerable(false);
        boss.invulnerableTime = 0;
        boss.hurtServer(run.level, run.level.damageSources().generic(), 1000.0f);
    }

    /** One whole phase change: the blow, the stagger, and the arm. */
    private static void phase(Run run) {
        burst(run);
        run.step();
        run.fight().staggerEnd = 0;
        run.step();
        run.pin();
    }

    @GameTest(maxTicks = 500)
    public void theSextonFightsInTheOpenAndTheCoffinsStaySealedInPhaseOne(GameTestHelper helper) {
        Run run = new Run(helper, 10040, true, 8, 4);
        try {
            run.start();
            helper.assertValueEqual(run.state().mobs.size(), 4, "the wave keeps its size: three sleepers and the Sexton");
            Mob boss = run.boss();
            helper.assertTrue(!dormant(boss) && OssuaryFinale.BOSS_NAME.equals(boss.getName().getString()),
                    "the Sexton stands awake and named");
            for (int i = 0; i < 3; i++) {
                helper.assertTrue(dormant(run.mob(i)), "mob " + i + " lies dormant in its coffin");
                helper.assertTrue(run.mob(i).entityTags().contains(FinaleWave.TAG),
                        "mob " + i + " carries the finale tag, so Restless never raises it");
            }
            helper.assertValueEqual(FinaleWave.alive(run.level, run.state()), 4, "the pad stays shut: four alive");
        } catch (RuntimeException | Error e) {
            run.close();
            throw e;
        }
        long start = run.server.getTickCount();
        helper.onEachTick(() -> {
            run.step();
            long t = run.server.getTickCount() - start;
            if (t == OssuaryRules.SKIP_DELAY_TICKS + OssuaryRules.CRACK_TICKS + 10) {
                helper.assertTrue(run.lidIntact(6, 3, 1) && run.lidIntact(13, 3, 1) && run.lidIntact(13, 12, -1),
                        "phase one: a member beside a coffin and a long wait open nothing");
                run.close();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void phaseTwoOpensCoffinsWithATellAsAMemberPasses(GameTestHelper helper) {
        Run run = new Run(helper, 10041, true, 8, 4);
        try {
            run.start();
            run.step();
            burst(run);
            helper.assertTrue(run.boss().isAlive(), "a killing blow does not skip phase two");
            run.step();
            helper.assertTrue(run.fight().phase() == 1 && run.boss().isInvulnerable(), "the Sexton staggers");
            run.fight().staggerEnd = 0;
            run.step();
            helper.assertTrue(run.lidIntact(6, 3, 1), "the stagger itself opens nothing");
        } catch (RuntimeException | Error e) {
            run.close();
            throw e;
        }
        long start = run.server.getTickCount();
        boolean[] sawTell = {false};
        helper.onEachTick(() -> {
            run.step();
            long t = run.server.getTickCount() - start;
            if (t == OssuaryRules.MIN_GAP_TICKS + 20) {
                helper.assertTrue(run.lidIntact(6, 3, 1), "mid tell: the lid still holds");
                helper.assertTrue(dormant(run.mob(0)), "mid tell: the occupant still sleeps");
                sawTell[0] = true;
            }
            if (t == OssuaryRules.MIN_GAP_TICKS + OssuaryRules.CRACK_TICKS + 10) {
                helper.assertTrue(sawTell[0], "the tell was seen first");
                helper.assertTrue(!run.lidIntact(6, 3, 1), "the lid has broken");
                helper.assertTrue(!dormant(run.mob(0)) && !run.mob(0).isNoAi() && !run.mob(0).isInvisible(),
                        "the first coffin's occupant stands awake");
                helper.assertTrue(dormant(run.mob(1)) && dormant(run.mob(2)),
                        "the other coffins are still shut: one at a time");
                helper.assertTrue(run.lidIntact(13, 3, 1) && run.lidIntact(13, 12, -1), "their lids hold");
                FinaleWave.floorEnded(run.level, run.record);
                helper.assertTrue(run.lidIntact(6, 3, 1), "floor end restores the broken lid");
                run.close();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void phaseThreeOpensASkippedCoffinButPhaseTwoDoesNot(GameTestHelper helper) {
        Run run = new Run(helper, 10042, true, 1, 14);
        try {
            run.start();
            run.step();
            phase(run);
        } catch (RuntimeException | Error e) {
            run.close();
            throw e;
        }
        long start = run.server.getTickCount();
        boolean[] third = {false};
        helper.onEachTick(() -> {
            run.step();
            long t = run.server.getTickCount() - start;
            if (t == OssuaryRules.SKIP_DELAY_TICKS + OssuaryRules.CRACK_TICKS + 10 && !third[0]) {
                helper.assertTrue(run.lidIntact(6, 3, 1) && run.lidIntact(13, 3, 1) && run.lidIntact(13, 12, -1),
                        "phase two: nobody is near, so time alone opens nothing (lids " + run.lidIntact(6, 3, 1) + " "
                        + run.lidIntact(13, 3, 1) + " " + run.lidIntact(13, 12, -1) + ", boss alive "
                        + (run.state().elite != null && run.level.getEntity(run.state().elite) instanceof Mob b && b.isAlive())
                        + ", fate " + run.sextonFate() + ", phase " + run.fight().phase() + ", member " + run.member.blockPosition() + ", origin "
                        + run.origin + ")");
                phase(run);
                third[0] = true;
            }
            if (third[0] && t == OssuaryRules.SKIP_DELAY_TICKS + 2L * OssuaryRules.CRACK_TICKS + 40) {
                helper.assertTrue(run.fight().phase() == 2, "phase three began");
                int open = 0;
                open += run.lidIntact(6, 3, 1) ? 0 : 1;
                open += run.lidIntact(13, 3, 1) ? 0 : 1;
                open += run.lidIntact(13, 12, -1) ? 0 : 1;
                helper.assertTrue(open >= 1, "phase three: a skipped coffin opened by itself");
                run.close();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void everyPhaseRunsInOrderAndTheKillOpensThePad(GameTestHelper helper) {
        Run run = new Run(helper, 10043, true, 1, 14);
        try {
            run.start();
            run.step();
            phase(run);
            helper.assertTrue(run.fight().phase() == 1, "phase two");
            phase(run);
            helper.assertTrue(run.fight().phase() == 2, "phase three");
            burst(run);
            helper.assertTrue(!run.boss().isAlive(), "the last phase dies");
            helper.assertTrue(!run.state().done, "the pad stays shut while the coffins hold the rest");
            for (UUID id : new ArrayList<>(run.state().mobs)) {
                Entity e = run.level.getEntity(id);
                if (e != null) {
                    e.discard();
                }
            }
            run.step();
            helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the last death opens the pad");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aSextonWhoIsGoneAnyOtherWayWakesEveryCoffin(GameTestHelper helper) {
        Run run = new Run(helper, 10044, true, 8, 4);
        try {
            run.start();
            run.step();
            run.boss().discard();
        } catch (RuntimeException | Error e) {
            run.close();
            throw e;
        }
        long start = run.server.getTickCount();
        helper.onEachTick(() -> {
            run.step();
            long t = run.server.getTickCount() - start;
            if (t == OssuaryRules.MIN_GAP_TICKS + OssuaryRules.CRACK_TICKS + 10) {
                helper.assertTrue(!run.lidIntact(6, 3, 1), "with the Sexton gone the nearest coffin opens: no soft lock");
                run.close();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void aLidBrokenByAPlayerFreesTheOccupants(GameTestHelper helper) {
        Run run = new Run(helper, 10045, true, 1, 14);
        try {
            run.start();
            helper.assertTrue(dormant(run.mob(0)), "asleep before the lid goes");
            run.level.setBlock(run.origin.offset(6, 2, 4), Blocks.AIR.defaultBlockState(), 3);
            run.step();
            helper.assertTrue(!dormant(run.mob(0)),
                    "a mined lid wakes what lies under it, so nobody is stuck asleep");
            helper.assertTrue(dormant(run.mob(1)) && dormant(run.mob(2)), "the other coffins are untouched");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aRoomWithNoCoffinsRunsTheDataWave(GameTestHelper helper) {
        Run run = new Run(helper, 10046, false, 1, 14);
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
