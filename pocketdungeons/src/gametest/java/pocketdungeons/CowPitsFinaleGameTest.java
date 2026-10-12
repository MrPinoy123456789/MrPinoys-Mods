package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * The Cow Pits' optional Specimen 0 finale (roadmap T30). A party that obeys the sign walks out
 * free; a party that feeds the pedestal cow wakes a script owned fight: Specimen 0 grows and takes
 * the boss bar, the floor's tagged cows turn, and the pad stays shut until the herd is dead.
 */
public final class CowPitsFinaleGameTest {

    @GameTest(maxTicks = 40)
    public void thePadOpensWhenTheSignIsObeyed(GameTestHelper helper) {
        Run run = new Run(helper, 9930);
        try {
            for (int i = 0; i < 5; i++) {
                run.step(true);
            }
            helper.assertTrue(!run.state().triggered, "cleared spawners never wake Specimen 0");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) == null,
                    "the pad is open while the sign is obeyed");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void feedingSpecimen0WakesTheHerd(GameTestHelper helper) {
        Run run = new Run(helper, 9931);
        try {
            Cow zero = run.cow(helper.absolutePos(new BlockPos(4, 2, 4)), true);
            Cow one = run.cow(helper.absolutePos(new BlockPos(6, 2, 6)), false);
            Cow two = run.cow(helper.absolutePos(new BlockPos(8, 2, 6)), false);
            CowPits.markFed(run.record);
            run.step(true);
            helper.assertTrue(run.state().triggered, "the feed mark wakes the finale");
            run.skipCountdown();
            run.step(false);
            FinaleWave.State state = run.state();
            helper.assertTrue(state.spawned, "the wave stands");
            helper.assertTrue(state.elite != null && state.elite.equals(zero.getUUID()),
                    "Specimen 0 is the elite");
            helper.assertTrue(state.mobs.contains(one.getUUID()) && state.mobs.contains(two.getUUID()),
                    "the herd is counted");
            helper.assertTrue(Math.abs(zero.getAttributeValue(Attributes.SCALE)
                    - PocketDungeonsConfig.cowSpecimenZeroScale()) < 0.01, "Specimen 0 grew");
            helper.assertTrue(zero.getMaxHealth() >= PocketDungeonsConfig.cowSpecimenZeroHealth(),
                    "Specimen 0 took the elite's health");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) != null,
                    "the pad stays shut while the fight is on");
            for (Cow cow : new Cow[]{zero, one, two}) {
                cow.discard();
            }
            run.step(false);
            helper.assertTrue(state.done && run.record.floor.finaleWon, "killing the herd wins");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) == null,
                    "the pad opens");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void thePadStaysShutWhileOneCowStands(GameTestHelper helper) {
        Run run = new Run(helper, 9932);
        try {
            Cow zero = run.cow(helper.absolutePos(new BlockPos(4, 2, 4)), true);
            Cow one = run.cow(helper.absolutePos(new BlockPos(6, 2, 6)), false);
            CowPits.markFed(run.record);
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertTrue(run.state().spawned, "the wave stands");
            zero.discard();
            run.step(false);
            helper.assertTrue(!run.state().done, "a standing herd cow keeps the finale going");
            helper.assertTrue(FinaleWave.padRefusal(run.server, run.record) != null,
                    "the pad stays shut");
            one.discard();
            run.step(false);
            helper.assertTrue(run.state().done, "the last cow ends it");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aFedMarkFromAnotherFloorCannotWake(GameTestHelper helper) {
        Run run = new Run(helper, 9933);
        try {
            CowPits.markFed(run.record);
            // The floor is regenerated (a new layout, a new seed): the old mark must not carry over.
            run.record.layout = run.layoutWithSeed(77L);
            for (int i = 0; i < 3; i++) {
                run.step(true);
            }
            helper.assertTrue(!run.state().triggered, "a stale mark does not wake the finale");
            helper.assertTrue(!CowPits.fedOn(run.record), "fedOn rejects another floor's mark");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theMarkIsForgottenWhenTheFloorEnds(GameTestHelper helper) {
        Run run = new Run(helper, 9934);
        try {
            Cow zero = run.cow(helper.absolutePos(new BlockPos(4, 2, 4)), true);
            CowPits.markFed(run.record);
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertTrue(run.state().spawned && run.state().elite != null, "the fight is on");
            FinaleWave.floorEnded(run.level, run.record);
            helper.assertTrue(!CowPits.fedOn(run.record), "the fed mark is forgotten");
            helper.assertTrue(FinaleWave.stateOf(run.record) == null, "the finale state is gone");
            helper.assertTrue(!zero.isAlive(), "the wave is discarded with the floor");
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
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout(origin, 1L), Set.of(),
                    player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = CowPits.DUNGEON;
            record.interval.nodeId = "the_big_barn";
        }

        private InstanceLayout layout(BlockPos origin, long seed) {
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
            return new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), seed, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
        }

        /** The same one cell layout with another seed, as a regenerated floor would have. */
        InstanceLayout layoutWithSeed(long seed) {
            return layout(record.layout.origin(), seed);
        }

        /** A tagged floor cow; {@code pedestal} gives it the Specimen 0 name. */
        Cow cow(BlockPos at, boolean pedestal) {
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

        /** One watch tick with no data wave behind the script, like the real Cow Pits. */
        void step(boolean due) {
            FinaleWave.step(server, level, record, null, List.of(member), due, "The Big Barn");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        void skipCountdown() {
            state().spawnTick = 0;
        }

        void close() {
            CowPits.forgetFed(record.slot);
            FinaleWave.teardown(level, record.slot);
        }
    }
}
