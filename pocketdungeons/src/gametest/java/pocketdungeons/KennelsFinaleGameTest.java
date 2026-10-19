package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Roadmap T22 and T03: the Kennels finale script on the phased spine, driven through {@link FinaleWave#step} as
 * the finale-script skill describes. Covers the handler's name and finale tag, the windup at phase two, the
 * empower (scale, anger, red glow team) at phase three, the cancel, the shrink back, burst damage not skipping a
 * phase, and that the team is gone when the floor ends.
 */
public final class KennelsFinaleGameTest {

    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:wolf", 2), new DungeonDef.FinaleMob("minecraft:pillager", 1)),
            0, null);
    private static final DungeonDef.Elite ELITE = new DungeonDef.Elite("minecraft:wolf", "The Alpha", 40, "");

    /** Takes over the spawn so the elite is part of the wave, as the shipped data would have it. */
    private static final class Wrapped implements FinaleScript {
        final KennelsFinale inner = new KennelsFinale();

        @Override
        public boolean spawnWave(FinaleContext ctx) {
            ctx.spawnNearParty("minecraft:wolf");
            ctx.spawnNearParty("minecraft:wolf");
            ctx.spawnNearParty("minecraft:pillager");
            ctx.spawnElite(ELITE);
            return true;
        }

        @Override
        public void onWaveSpawned(FinaleContext ctx) {
            inner.onWaveSpawned(ctx);
        }

        @Override
        public void onTick(FinaleContext ctx) {
            inner.onTick(ctx);
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
    public void theHandlerIsNamedAndTheKennelsHasAScript(GameTestHelper helper) {
        helper.assertTrue(registeredForKennels(), "the Kennels dungeon has a finale script");
        Run run = new Run(helper, 10020);
        try {
            run.start();
            Pillager handler = run.handler();
            helper.assertTrue(handler.getCustomName() != null
                    && handler.getCustomName().getString().equals(KennelsFinale.HANDLER_NAME), "the handler is named");
            helper.assertTrue(handler.entityTags().contains(FinaleWave.TAG),
                    "a finale mob, so Restless leaves it down");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void phaseTwoStartsTheWindupAndPhaseThreeGrowsThePack(GameTestHelper helper) {
        Run run = new Run(helper, 10021);
        try {
            run.start();
            run.step();
            helper.assertTrue(!run.handler().isCelebrating(), "in phase one the handler waits");
            run.burst();
            helper.assertTrue(run.alpha().isAlive(), "a killing blow does not skip phase two");
            run.step();
            helper.assertTrue(run.alpha().isInvulnerable() && !run.handler().isCelebrating(),
                    "the Alpha staggers before the handler moves");
            run.fight().staggerEnd = 0;
            run.step();
            helper.assertTrue(run.handler().isCelebrating(), "phase two: the handler raises his arm");
            helper.assertValueEqual(run.wolf(0).getAttribute(Attributes.SCALE).getBaseValue(), 1.0,
                    "no growth during the windup");
            helper.assertTrue(!teamExists(run), "no team during the windup");
            run.burst();
            helper.assertTrue(run.alpha().isAlive(), "a killing blow does not skip phase three");
            run.step();
            run.fight().staggerEnd = 0;
            run.step();
            helper.assertValueEqual(run.wolf(0).getAttribute(Attributes.SCALE).getBaseValue(), 1.0,
                    "phase three still waits out the five second tell");
            run.state2().windupEnd = 0;
            run.step();
            for (int n = 0; n < 3; n++) {
                Wolf wolf = run.wolf(n);
                helper.assertTrue(Math.abs(wolf.getAttribute(Attributes.SCALE).getBaseValue() - 1.4) < 1e-6,
                        "wolf " + n + " is 1.4 times");
                helper.assertTrue(wolf.hasGlowingTag(), "wolf " + n + " glows");
                helper.assertTrue(wolf.isAngry() || wolf.getTarget() != null, "wolf " + n + " is angry");
                helper.assertTrue(run.level.getScoreboard().getPlayersTeam(wolf.getScoreboardName()) != null,
                        "wolf " + n + " is in the red team");
            }
            helper.assertTrue(!run.handler().isCelebrating(), "the arm comes down");
            helper.assertTrue(teamExists(run), "the team exists while the pack is large");
            run.burst();
            helper.assertTrue(!run.alpha().isAlive(), "the Alpha dies in the last phase");
            FinaleWave.floorEnded(run.level, run.record);
            helper.assertTrue(!teamExists(run), "the floor ending removes the team");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void killingTheHandlerBeforePhaseThreeCancelsIt(GameTestHelper helper) {
        Run run = new Run(helper, 10022);
        try {
            run.start();
            run.phase();
            helper.assertTrue(run.handler().isCelebrating(), "the windup is on");
            run.handler().discard();
            run.phase();
            run.state2().windupEnd = 0;
            run.step();
            run.step();
            for (int n = 0; n < 3; n++) {
                helper.assertValueEqual(run.wolf(n).getAttribute(Attributes.SCALE).getBaseValue(), 1.0,
                        "wolf " + n + " stays normal");
            }
            helper.assertTrue(!teamExists(run), "no team was made");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void killingTheHandlerAfterwardsShrinksThePack(GameTestHelper helper) {
        Run run = new Run(helper, 10023);
        try {
            run.start();
            run.phase();
            run.state2().windupEnd = 0;
            run.phase();
            helper.assertTrue(run.wolf(0).getAttribute(Attributes.SCALE).getBaseValue() > 1.3, "the pack grew");
            run.handler().discard();
            run.step();
            for (int n = 0; n < 3; n++) {
                Wolf wolf = run.wolf(n);
                helper.assertValueEqual(wolf.getAttribute(Attributes.SCALE).getBaseValue(), 1.0,
                        "wolf " + n + " is back to normal size");
                helper.assertTrue(!wolf.hasGlowingTag(), "wolf " + n + " no longer glows");
            }
            helper.assertTrue(!teamExists(run), "the team is removed");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void aDeadHandlerAtPhaseTwoDoesNothing(GameTestHelper helper) {
        Run run = new Run(helper, 10024);
        try {
            run.start();
            run.handler().discard();
            run.phase();
            run.phase();
            run.state2().windupEnd = 0;
            run.step();
            helper.assertValueEqual(run.wolf(0).getAttribute(Attributes.SCALE).getBaseValue(), 1.0,
                    "nothing happened");
            helper.assertTrue(!teamExists(run), "no team");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    private static boolean registeredForKennels() {
        return FinaleScripts.forDungeon("pocketdungeons:kennels") instanceof KennelsFinale;
    }

    private static boolean teamExists(Run run) {
        return run.level.getScoreboard().getPlayerTeam(KennelsFinaleRules.teamName(run.record.slot)) != null;
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
            this.dungeonId = "pocketdungeons:test_kennels_" + slot;
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            player.setInvulnerable(true);
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
            FinaleScripts.register(dungeonId, new Wrapped());
        }

        void step() {
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Den");
        }

        /** Triggers, skips the countdown and lets the wave spawn. */
        void start() {
            FinaleWave.step(server, level, record, FINALE, List.of(member), true, "Test Den");
            state().spawnTick = 0;
            step();
            helper.assertValueEqual(state().mobs.size(), 4, "two wolves, the handler and the Alpha");
            step();
        }

        BossFight fight() {
            return (BossFight) state().scriptState.get(BossFight.class);
        }

        Mob alpha() {
            return (Mob) level.getEntity(state().elite);
        }

        /** A killing blow on the Alpha: the spine's clamp holds it at its next bound. */
        void burst() {
            Mob alpha = alpha();
            alpha.invulnerableTime = 0;
            alpha.hurtServer(level, level.damageSources().generic(), 1000.0f);
        }

        /** One whole phase change: the blow, the stagger, and the arm. */
        void phase() {
            burst();
            step();
            fight().staggerEnd = 0;
            step();
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        KennelsFinale.Run state2() {
            return (KennelsFinale.Run) state().scriptState.get(KennelsFinale.Run.class);
        }

        Pillager handler() {
            return (Pillager) level.getEntity(state().mobs.get(2));
        }

        /** Wolf 0 and 1 are the pack, wolf 2 is the Alpha. */
        Wolf wolf(int n) {
            UUID id = state().mobs.get(n < 2 ? n : 3);
            Entity entity = level.getEntity(id);
            helper.assertTrue(entity instanceof Wolf, "wolf " + n + " stands");
            return (Wolf) entity;
        }

        void close() {
            FinaleWave.teardown(level, record.slot);
            FinaleScripts.unregister(dungeonId);
            KennelsFinale.removeTeam(server, record.slot);
        }
    }
}
