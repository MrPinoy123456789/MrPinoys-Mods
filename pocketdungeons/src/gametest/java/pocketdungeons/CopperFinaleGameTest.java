package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Roadmap T32: the Master Furnace's furnace clock and vent floor, driven through
 * {@link FinaleWave#step} as the finale-script skill describes. The tests start the
 * {@link CopperClock}, blow it, watch the Foreman arrive without cleared spawners or a
 * countdown, check the vents sweep in a fixed order and ignite whoever stands on one, and
 * that a Foreman standing in a water vat weakens.
 */
public final class CopperFinaleGameTest {

    /** The wave the shipped copper_works.json defines: husks, skeletons and the Foreman. */
    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:husk", 4),
                    new DungeonDef.FinaleMob("minecraft:skeleton", 2)), 50,
            new DungeonDef.Elite("minecraft:vindicator", "The Foreman", 60, "minecraft:copper_axe"));

    @GameTest(maxTicks = 40)
    public void theClockStartsCountsDownAndBlows(GameTestHelper helper) {
        Run run = new Run(helper, 9935);
        try {
            helper.assertTrue(FinaleScripts.forDungeon(CopperClock.DUNGEON) instanceof CopperFinale,
                    "the Copper Works has a finale script");
            CopperClock.Clock clock = run.record.interval.copperClock;
            helper.assertTrue(clock.deadline == 0, "no clock before the dungeon is entered");
            CopperClock.tick(run.server, run.record);
            helper.assertTrue(clock.deadline - run.server.getTickCount()
                    == PocketDungeonsConfig.copperClockMinutes() * 1200L,
                    "the clock starts at the knob's minutes");
            helper.assertTrue(!CopperClock.expired(run.record), "a fresh clock has not blown");
            clock.deadline = run.server.getTickCount();
            CopperClock.tick(run.server, run.record);
            helper.assertTrue(clock.blown && CopperClock.expired(run.record),
                    "zero blows the furnace and it stays blown");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aBlownClockBringsTheForemanWithoutSpawnersOrCountdown(GameTestHelper helper) {
        Run run = new Run(helper, 9936);
        try {
            run.step(false);
            helper.assertTrue(!run.state().triggered, "a live clock never triggers the finale");
            run.blow();
            run.step(false);
            helper.assertTrue(run.state().triggered, "a blown clock wakes the fight on its own");
            helper.assertTrue(run.state().spawnTick <= run.server.getTickCount(),
                    "and skips the countdown entirely");
            run.step(false);
            helper.assertTrue(run.state().spawned, "the Foreman's wave stands at once");
            Entity elite = run.level.getEntity(run.state().elite);
            helper.assertTrue(elite instanceof Mob && "The Foreman".equals(elite.getName().getString()),
                    "the Foreman leads it");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void clearedSpawnersStillTriggerTheWaveWithItsCountdown(GameTestHelper helper) {
        Run run = new Run(helper, 9937);
        try {
            CopperClock.tick(run.server, run.record);
            run.step(true);
            helper.assertTrue(run.state().triggered, "cleared spawners still trigger it");
            helper.assertTrue(run.state().spawnTick > run.server.getTickCount(),
                    "but the countdown still runs while the clock is alive");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aVentPulseSweepsInOrderAndIgnitesWhoeverStandsOnIt(GameTestHelper helper) {
        Run run = new Run(helper, 9938);
        try {
            BlockPos one = run.vent(4, 1, 4);
            run.vent(7, 1, 4);
            run.vent(11, 1, 7);
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertTrue(run.state().spawned, "the wave stands");
            CopperFinale.Vents vents = run.vents();
            helper.assertValueEqual(vents.tiles, List.of(one, one.offset(3, 0, 0), one.offset(7, 0, 3)),
                    "the sweep is the fixed serpentine order");
            run.member.teleportTo(run.level, one.getX() + 0.5, one.getY() + 1.0, one.getZ() + 0.5,
                    Set.of(), 0.0F, 0.0F, false);
            vents.armed = true;
            vents.tellAt = run.server.getTickCount();
            helper.assertTrue(run.member.getRemainingFireTicks() <= 0, "nobody burns before the pulse");
            run.step(false);
            helper.assertTrue(vents.told, "the tell starts");
            helper.assertTrue(vents.fireAt == run.server.getTickCount() + CopperClockRules.TELL_TICKS,
                    "the fire is pinned a full two seconds out");
            vents.fireAt = run.server.getTickCount();
            run.step(false);
            helper.assertTrue(run.member.getRemainingFireTicks() > 0, "the pulse ignites the tile");
            helper.assertValueEqual(vents.next, 1, "the sweep moves to the next vent");
            helper.assertTrue(vents.tellAt > run.server.getTickCount(),
                    "and the next pulse is scheduled ahead");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aForemanInAVatWeakens(GameTestHelper helper) {
        Run run = new Run(helper, 9939);
        try {
            BlockPos vat = run.vat(6, 1, 10);
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertTrue(run.state().spawned && run.state().elite != null, "the wave stands");
            CopperFinale.Vents vents = run.vents();
            helper.assertValueEqual(vents.vats, List.of(vat), "the cauldron reads as a vat");
            Mob foreman = (Mob) run.level.getEntity(run.state().elite);
            foreman.setPos(vat.getX() + 0.5, vat.getY(), vat.getZ() + 0.5);
            vents.nextVatCheckAt = run.server.getTickCount();
            run.step(false);
            helper.assertTrue(foreman.hasEffect(MobEffects.WEAKNESS), "a vat weakens the Foreman");
            helper.assertTrue(foreman.hasEffect(MobEffects.SLOWNESS), "and slows him");
            helper.assertTrue(foreman.getHealth() < foreman.getMaxHealth(), "and the water hurts him");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theVentsWaitForTheForemansSecondPhase(GameTestHelper helper) {
        Run run = new Run(helper, 9940);
        try {
            run.vent(4, 1, 4);
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertTrue(run.state().spawned, "the wave stands");
            CopperFinale.Vents vents = run.vents();
            helper.assertTrue(!vents.armed && !vents.told, "the floor stays quiet while he is fresh");
            run.step(false);
            helper.assertTrue(!vents.told, "still quiet a tick later");
            Mob foreman = (Mob) run.level.getEntity(run.state().elite);
            foreman.setHealth(foreman.getMaxHealth() * 0.5f);
            run.step(false);
            helper.assertTrue(foreman.isInvulnerable() && foreman.isNoAi(),
                    "crossing two thirds staggers him");
            run.fight().staggerEnd = 0;
            run.step(false);
            helper.assertTrue(!foreman.isInvulnerable() && !foreman.isNoAi(),
                    "the stagger lifts when it ends");
            helper.assertTrue(vents.armed && vents.tellAt > run.server.getTickCount(),
                    "and the vents wake");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void burstDamageCannotSkipAPhase(GameTestHelper helper) {
        Run run = new Run(helper, 9941);
        try {
            run.vent(4, 1, 4);
            run.step(true);
            run.skipCountdown();
            run.step(false);
            helper.assertTrue(run.state().spawned, "the wave stands");
            CopperFinale.Vents vents = run.vents();
            Mob foreman = (Mob) run.level.getEntity(run.state().elite);
            // A hit that carries under both bounds at once still runs both phases.
            foreman.setHealth(foreman.getMaxHealth() * 0.2f);
            run.step(false);
            helper.assertTrue(run.fight().phase() == 1 && foreman.isNoAi(),
                    "only the second phase begins");
            run.fight().staggerEnd = 0;
            run.step(false);
            helper.assertTrue(vents.armed && !vents.cornered, "the vents wake, not the cornered bite");
            run.step(false);
            helper.assertTrue(run.fight().phase() == 2 && foreman.isNoAi(),
                    "the last phase staggers straight after");
            run.fight().staggerEnd = 0;
            run.step(false);
            helper.assertTrue(vents.cornered, "cornered arms on its own stagger");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theCorneredPhaseBitesHarderInAVat(GameTestHelper helper) {
        Run run = new Run(helper, 9942);
        try {
            BlockPos vat = run.vat(6, 1, 10);
            run.step(true);
            run.skipCountdown();
            run.step(false);
            Mob foreman = (Mob) run.level.getEntity(run.state().elite);
            CopperFinale.Vents vents = run.vents();
            vents.cornered = true;
            foreman.setPos(vat.getX() + 0.5, vat.getY(), vat.getZ() + 0.5);
            float before = foreman.getHealth();
            vents.nextVatCheckAt = run.server.getTickCount();
            run.step(false);
            double took = before - foreman.getHealth();
            helper.assertTrue(took > PocketDungeonsConfig.copperForemanVatDamage(),
                    "a cornered Foreman takes the multiplied bite, took " + took);
        } finally {
            run.close();
        }
        helper.succeed();
    }

    /** One Copper Works final floor with one mock member, a stone floor and a vent band. */
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;
        final BlockPos origin;

        Run(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
            player.teleportTo(level, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, Set.of(), 0.0F, 0.0F,
                    false);
            this.origin = helper.absolutePos(BlockPos.ZERO);
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
            InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                    player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = CopperClock.DUNGEON;
            record.interval.nodeId = "master_furnace";
            // Open ground under the member, so the wave has somewhere to stand.
            for (int dx = -12; dx <= 14; dx++) {
                for (int dz = -12; dz <= 14; dz++) {
                    level.setBlock(origin.offset(dx, 1, dz), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }

        /** A vent grate in the floor at the cell offset, as the room's decor leaves them. */
        BlockPos vent(int x, int y, int z) {
            BlockPos pos = origin.offset(x, y, z);
            level.setBlock(pos, Blocks.COPPER_GRATE.waxed().unaffected().defaultBlockState(), 3);
            return pos;
        }

        /** A water vat (a filled cauldron) at the cell offset. */
        BlockPos vat(int x, int y, int z) {
            BlockPos pos = origin.offset(x, y, z);
            level.setBlock(pos, Blocks.WATER_CAULDRON.defaultBlockState(), 3);
            return pos;
        }

        /** One watch tick; {@code due} says the floor's spawners are cleared. */
        void step(boolean due) {
            FinaleWave.step(server, level, record, FINALE, List.of(member), due, "The Master Furnace");
        }

        /** Runs the clock's tick until the furnace blows. */
        void blow() {
            CopperClock.tick(server, record);
            record.interval.copperClock.deadline = server.getTickCount();
            CopperClock.tick(server, record);
            helper.assertTrue(CopperClock.expired(record), "the furnace blew");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        CopperFinale.Vents vents() {
            CopperFinale.Vents vents =
                    (CopperFinale.Vents) state().scriptState.get(CopperFinale.Vents.class);
            helper.assertTrue(vents != null && vents.scanned, "the vent floor was scanned");
            return vents;
        }

        BossFight fight() {
            return (BossFight) state().scriptState.get(BossFight.class);
        }

        void skipCountdown() {
            state().spawnTick = 0;
        }

        void close() {
            FinaleWave.teardown(level, record.slot);
        }
    }
}
