package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Backlog F2: Specimen 0 fights on the {@link BossFight} spine. Burst damage walks the three phases in
 * order (herd quiet, herd charges, herd scales), the pad stays shut until the kill, and a Specimen 0
 * killed early turns the placid herd so the floor still finishes.
 */
public final class CowPitsPhasesGameTest {

    @GameTest(maxTicks = 60)
    public void burstDamageWalksThreePhasesAndTheKillOpensThePad(GameTestHelper helper) {
        Run run = new Run(helper, 10100);
        try {
            Cow zero = run.cow(4, true);
            Cow one = run.cow(6, false);
            CowPits.markFed(run.record);
            run.wake();
            helper.assertTrue(run.herd().angry.contains(zero.getUUID()), "Specimen 0 charges from the start");
            helper.assertTrue(!run.herd().angry.contains(one.getUUID()), "the herd grazes in phase one");
            helper.assertTrue(Math.abs(one.getAttributeValue(Attributes.SCALE) - 1.0) < 0.01, "and is unscaled");
            // One burst carries Specimen 0 under both bounds; the phases still come one at a time.
            zero.setHealth(zero.getMaxHealth() * 0.2f);
            run.step();
            helper.assertValueEqual(run.fight().phase(), 1, "only the second phase begins");
            helper.assertTrue(zero.isInvulnerable() && zero.isNoAi(), "Specimen 0 staggers");
            helper.assertTrue(PocketDungeonsConfig.bossPhaseStaggerSeconds() * 20 >= 40, "the tell lasts 2 seconds");
            helper.assertTrue(!run.herd().angry.contains(one.getUUID()), "the herd waits out the stagger");
            run.fight().staggerEnd = 0;
            run.step();
            helper.assertTrue(run.herd().angry.contains(one.getUUID()), "phase two: the herd charges");
            helper.assertTrue(Math.abs(one.getAttributeValue(Attributes.SCALE) - 1.0) < 0.01, "still unscaled");
            run.step();
            helper.assertValueEqual(run.fight().phase(), 2, "the last phase staggers straight after");
            run.fight().staggerEnd = 0;
            run.step();
            helper.assertTrue(Math.abs(one.getAttributeValue(Attributes.SCALE) - CowPitsFinale.HERD_SCALE) < 0.01,
                    "phase three: the herd scales");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) != null, "the pad is shut mid fight");
            zero.discard();
            one.discard();
            run.step();
            helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the kill wins");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) == null, "the pad opens");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void aSpecimenKilledEarlyTurnsTheHerdSoTheFloorFinishes(GameTestHelper helper) {
        Run run = new Run(helper, 10101);
        try {
            Cow zero = run.cow(4, true);
            Cow one = run.cow(6, false);
            CowPits.markFed(run.record);
            run.wake();
            zero.discard();
            run.step();
            helper.assertTrue(run.herd().angry.contains(one.getUUID()), "the herd turns when Specimen 0 falls");
            helper.assertTrue(!run.state().done, "the herd still has to be cleared");
            one.discard();
            run.step();
            helper.assertTrue(run.state().done, "the floor finishes");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void anUnwokenSpecimenNeverStartsAFight(GameTestHelper helper) {
        Run run = new Run(helper, 10102);
        try {
            run.cow(4, true);
            for (int i = 0; i < 4; i++) {
                run.step();
            }
            helper.assertTrue(!run.state().triggered && run.fight() == null, "no fight, no phases");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) == null, "the pad is open");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    /** A hand built Cow Pits run on its final floor, one mock member. */
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;

        Run(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
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
            record.interval.dungeonId = CowPits.DUNGEON;
            record.interval.nodeId = "the_big_barn";
        }

        Cow cow(int x, boolean pedestal) {
            BlockPos at = helper.absolutePos(new BlockPos(x, 2, x));
            Cow cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
            helper.assertTrue(cow != null, "a cow spawns");
            cow.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            cow.addTag(CowPits.TAG);
            if (pedestal) {
                cow.setCustomName(Component.literal(CowPits.WARD_PEDESTAL_NAME));
                cow.setCustomNameVisible(true);
            }
            level.addFreshEntity(cow);
            return cow;
        }

        void step() {
            FinaleWave.step(server, level, record, null, List.of(member), true, "The Big Barn");
        }

        /** Triggers, skips the countdown and lets the wave stand; one more step arms phase one. */
        void wake() {
            step();
            helper.assertTrue(state().triggered, "the feed wakes the finale");
            state().spawnTick = 0;
            step();
            helper.assertTrue(state().spawned, "the wave stands");
            step();
            helper.assertValueEqual(fight().phase(), 0, "the fight opens in phase one");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        BossFight fight() {
            return (BossFight) state().scriptState.get(BossFight.class);
        }

        CowPitsFinale.Herd herd() {
            return (CowPitsFinale.Herd) state().scriptState.get(CowPitsFinale.Herd.class);
        }

        void close() {
            CowPits.forgetFed(record.slot);
            FinaleWave.teardown(level, record.slot);
        }
    }
}
