package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T2 leader grace (decision 2026-10-21): the party leader's reconnect grace is
 * {@code ownerReconnectGraceSeconds} = 600, the run ends on the next watcher tick once every
 * guest has also left, nobody commits a door while the leader is away, and the 5 and
 * 1 minute warnings each fire once per hold.
 */
public final class LeaderGraceGameTest {

    /**
     * The hold sets a 600 second deadline; when the last guest also leaves the run ends on the
     * next watcher tick rather than waiting out the grace.
     */
    @GameTest(maxTicks = 20)
    public void guestsLeavingDuringHoldEndTheRun(GameTestHelper helper) {
        int grace = PocketDungeonsConfig.ownerReconnectGraceSeconds();
        helper.assertTrue(grace > 0, "the leader's reconnect grace is on");
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer guest = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        int slot = 9913;
        ReturnPoint outside = new ReturnPoint(Level.OVERWORLD, new Vec3(4.5, 70.0, 4.5), 0.0f, 0.0f);
        InstanceRecord record = clearedRecord(helper, server, slot, owner);
        record.members.put(owner.getUUID(), outside);
        record.members.put(guest.getUUID(), outside);
        register(record, slot, owner, guest);
        try {
            RunLifecycle.dropMember(server, record, owner.getUUID(), owner, "member disconnected", true);
            long deadline = server.overworld().getGameTime() + grace * 20L;
            helper.assertValueEqual(deadline, record.ownerAbsentUntilTick,
                    "the hold deadline is now plus the grace");
            helper.assertTrue(InstanceRegistry.bySlot.get(slot) == record,
                    "the run is held while a guest remains");

            // The warnings fire once each, at their own thresholds.
            RunLifecycle.watchOwnerGrace(server, record, deadline - 4 * 60 * 20L);
            helper.assertTrue(record.ownerGraceWarned5, "the 5 minute warning fires");
            helper.assertTrue(!record.ownerGraceWarned1, "the 1 minute warning has not fired yet");
            RunLifecycle.watchOwnerGrace(server, record, deadline - 4 * 60 * 20L + 1);
            helper.assertTrue(!record.ownerGraceWarned1, "the 1 minute warning still has not fired");

            RunLifecycle.dropMember(server, record, guest.getUUID(), guest, "member left the dimension");
            helper.assertTrue(record.members.isEmpty(), "the last guest is gone");
            helper.assertTrue(
                    RunLifecycle.watchOwnerGrace(server, record, server.overworld().getGameTime()),
                    "the watcher ends the run once nobody remains");
            helper.assertTrue(InstanceRegistry.bySlot.get(slot) == null,
                    "the slot is freed when every member has left");
        } finally {
            unregister(slot, owner, guest);
        }
        helper.succeed();
    }

    /**
     * A guest's door commit is refused while the leader is away; the same commit passes
     * after the leader rejoins inside the grace.
     */
    @GameTest(maxTicks = 20)
    public void noDoorCommitsWhileTheLeaderIsAway(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer guest = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        int slot = 9914;
        ReturnPoint outside = new ReturnPoint(Level.OVERWORLD, new Vec3(4.5, 70.0, 4.5), 0.0f, 0.0f);
        InstanceRecord record = clearedRecord(helper, server, slot, owner);
        record.members.put(owner.getUUID(), outside);
        record.members.put(guest.getUUID(), outside);
        // A committable state: a selected step, a live preview, and the free mine doors.
        PlanCell cell = new PlanCell(0, 0);
        record.floor.selectedStep = 1;
        record.floor.previewPlan = new DungeonPlan(1L, Set.of(cell), Map.of(), Map.of(), Map.of(),
                Set.of(), cell, cell, List.of(cell), null);
        record.floor.previewBiasGeneration = PlaytestBias.generation();
        record.interval.endlessMine = true;
        record.interval.dungeonId = EndlessMineRules.MINE_DUNGEON_ID;
        register(record, slot, owner, guest);
        try {
            helper.assertTrue(RunLifecycle.checkCommitGates(guest, server, record) != null,
                    "the guest may commit while the leader is present");

            RunLifecycle.dropMember(server, record, owner.getUUID(), owner, "member disconnected", true);
            helper.assertTrue(RunLifecycle.checkCommitGates(guest, server, record) == null,
                    "no door commits while the leader is away");
            helper.assertTrue(record.phase == RunSession.Phase.FLOOR_CLEARED,
                    "the refused commit leaves the phase alone");

            helper.assertTrue(Instances.rejoinOwnedInstance(server, owner, outside),
                    "the leader rejoins inside the grace");
            helper.assertValueEqual(0L, record.ownerAbsentUntilTick, "the rejoin lifts the hold");
            helper.assertTrue(RunLifecycle.checkCommitGates(guest, server, record) != null,
                    "the guest may commit again once the leader is back");
        } finally {
            unregister(slot, owner, guest);
        }
        helper.succeed();
    }

    /** A leader alone in their run is untouched by the owner-away hold: they drop and re-enter as before. */
    @GameTest(maxTicks = 20)
    public void aLeaderAloneGetsNoHold(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        int slot = 9915;
        InstanceRecord record = clearedRecord(helper, server, slot, owner);
        record.members.put(owner.getUUID(),
                new ReturnPoint(Level.OVERWORLD, new Vec3(4.5, 70.0, 4.5), 0.0f, 0.0f));
        register(record, slot, owner);
        try {
            RunLifecycle.dropMember(server, record, owner.getUUID(), owner, "member disconnected", true);
            helper.assertValueEqual(0L, record.ownerAbsentUntilTick,
                    "a leader alone never gets an owner-away hold");
            helper.assertTrue(InstanceRegistry.bySlot.get(slot) == record,
                    "the run survives for the leader's free re-entry");
        } finally {
            unregister(slot, owner);
        }
        helper.succeed();
    }

    // ---- scaffolding ---------------------------------------------------------------------------

    private static InstanceRecord clearedRecord(GameTestHelper helper, MinecraftServer server, int slot,
                                                ServerPlayer owner) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(), layout,
                Set.of(), owner.getUUID(), false);
        record.floor.theme = "pocketdungeons:test_leader_grace";
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.floor.completed.add(owner.getUUID());
        record.interval.floorSteps.add(1);
        record.interval.floorIndex = 1;
        return record;
    }

    private static void register(InstanceRecord record, int slot, ServerPlayer... members) {
        InstanceRegistry.bySlot.put(slot, record);
        InstanceRegistry.usedSlots.add(slot);
        for (ServerPlayer member : members) {
            InstanceRegistry.byMember.put(member.getUUID(), record);
        }
    }

    private static void unregister(int slot, ServerPlayer... members) {
        InstanceRegistry.bySlot.remove(slot);
        InstanceRegistry.usedSlots.remove(slot);
        for (ServerPlayer member : members) {
            InstanceRegistry.byMember.remove(member.getUUID());
        }
    }

    private static ServerPlayer standInALoadedChunk(GameTestHelper helper) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }
}
