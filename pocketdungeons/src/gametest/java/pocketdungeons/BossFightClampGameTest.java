package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The BossFight spine's death clamp: a killing blow on a boss with a phase still to come leaves it at the next
 * bound, so burst damage runs the phases in order and never skips one; only the last phase can die.
 */
public final class BossFightClampGameTest {

    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:zombie", 1)), 50, null);

    /** A test script: one zombie boss under a three phase fight that records which phases armed. */
    private static final class Script implements FinaleScript {
        final List<Integer> armed = new ArrayList<>();
        final List<BossFight.Step> phases = List.of(
                new BossFight.Step(1.0, List.of(), (ctx, boss) -> armed.add(0)),
                new BossFight.Step(2.0 / 3.0, List.of("two"), (ctx, boss) -> armed.add(1)),
                new BossFight.Step(1.0 / 3.0, List.of("three"), (ctx, boss) -> armed.add(2)));

        @Override
        public boolean spawnWave(FinaleContext ctx) {
            return ctx.spawnElite(new DungeonDef.Elite("minecraft:zombie", "Test Boss", 60, "")) != null;
        }

        @Override
        public void onTick(FinaleContext ctx) {
            ctx.scriptState(BossFight.class, () -> new BossFight(phases)).tick(ctx);
        }
    }

    @GameTest(maxTicks = 60)
    public void burstDamageRunsEveryPhaseInOrderAndOnlyTheLastCanDie(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        @SuppressWarnings("removal")
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        member.setGameMode(GameType.SURVIVAL);
        member.setInvulnerable(true);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        member.teleportTo(level, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, Set.of(), 0.0F, 0.0F, false);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        int slot = 10000;
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                member.getUUID(), false);
        record.members.put(member.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.phase = RunSession.Phase.ACTIVE;
        String id = "pocketdungeons:test_boss_clamp_" + slot;
        record.interval.dungeonId = id;
        Script script = new Script();
        FinaleScripts.register(id, script);
        try {
            FinaleWave.step(server, level, record, FINALE, List.of(member), true, "Test Room");
            FinaleWave.stateOf(record).spawnTick = 0;
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Room");
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Room");
            Mob boss = (Mob) level.getEntity(FinaleWave.stateOf(record).elite);
            helper.assertTrue(boss != null && script.armed.equals(List.of(0)), "the opening phase armed");
            BossFight fight = (BossFight) FinaleWave.stateOf(record).scriptState.get(BossFight.class);
            // First killing blow: the boss survives at the 2/3 bound and the next poll starts phase two.
            boss.hurtServer(level, level.damageSources().generic(), 1000.0f);
            helper.assertTrue(boss.isAlive(), "a killing blow in phase one does not kill");
            helper.assertTrue(Math.abs(boss.getHealth() - boss.getMaxHealth() * 2.0f / 3.0f) < 0.01f,
                    "it leaves the boss at the two thirds bound");
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Room");
            helper.assertTrue(fight.phase() == 1 && fight.staggering() && boss.isInvulnerable(),
                    "the poll staggers it into phase two");
            fight.staggerEnd = 0;
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Room");
            helper.assertTrue(script.armed.equals(List.of(0, 1)), "phase two armed after the stagger");
            // Second killing blow: held at the 1/3 bound.
            boss.invulnerableTime = 0;
            boss.hurtServer(level, level.damageSources().generic(), 1000.0f);
            helper.assertTrue(boss.isAlive() && Math.abs(boss.getHealth() - boss.getMaxHealth() / 3.0f) < 0.01f,
                    "a killing blow in phase two holds at the one third bound");
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Room");
            fight.staggerEnd = 0;
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Room");
            helper.assertTrue(script.armed.equals(List.of(0, 1, 2)), "phase three armed");
            // The last phase can die.
            boss.invulnerableTime = 0;
            boss.hurtServer(level, level.damageSources().generic(), 1000.0f);
            helper.assertTrue(!boss.isAlive(), "the last phase dies to a killing blow");
        } finally {
            FinaleWave.teardown(level, slot);
            FinaleScripts.unregister(id);
        }
        helper.succeed();
    }
}
