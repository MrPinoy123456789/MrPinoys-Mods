package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * PD-191 and PD-192 (owner ruling 2026-10-09): leaving a party for any reason, including to join another,
 * cashes the haul out in full between floors and fails it (the fail share) on a floor. An owner who leaves a
 * floor in progress fails the dungeon for everyone.
 */
public final class JoinFromDungeonGameTest {

    @GameTest(maxTicks = 20)
    public void aRiderWhoJoinsAnotherPartyMidFloorFailsTheirHaul(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9981;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        log.setHaul(owner.getUUID(), 10);
        log.setHaul(rider.getUUID(), 10);
        int bankedBefore = banked(log, rider);
        try {
            PartyService.leaveOwnDungeon(server, rider, record);
            helper.assertTrue(!InstanceRegistry.byMember.containsKey(rider.getUUID()), "the rider is out of the dungeon");
            helper.assertTrue(InstanceRegistry.byMember.get(owner.getUUID()) == record, "the owner's run goes on");
            helper.assertValueEqual(log.haulOf(rider.getUUID()), 0, "the rider's haul is settled");
            helper.assertValueEqual(log.haulOf(owner.getUUID()), 10, "the owner's haul is untouched");
            helper.assertValueEqual(banked(log, rider) - bankedBefore, 5, "on a floor it fails: half of 10 banks");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void anOwnerWhoJoinsAnotherPartyFailsTheDungeonForEveryone(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9982;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        log.setHaul(owner.getUUID(), 10);
        log.setHaul(rider.getUUID(), 6);
        int ownerBefore = banked(log, owner);
        int riderBefore = banked(log, rider);
        try {
            PartyService.leaveOwnDungeon(server, owner, record);
            helper.assertTrue(InstanceRegistry.bySlot.get(slot) != record, "the dungeon is closed");
            helper.assertTrue(!InstanceRegistry.byMember.containsKey(owner.getUUID()), "the owner is out");
            helper.assertTrue(!InstanceRegistry.byMember.containsKey(rider.getUUID()), "and so is the rider");
            helper.assertValueEqual(log.haulOf(owner.getUUID()), 0, "the owner's haul is settled");
            helper.assertValueEqual(log.haulOf(rider.getUUID()), 0, "so is the rider's");
            helper.assertValueEqual(banked(log, owner) - ownerBefore, 5, "the owner fails too: half of 10");
            helper.assertValueEqual(banked(log, rider) - riderBefore, 3, "and so does the rider: half of 6");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** A rider who walks out of a floor in progress fails their haul; the run goes on. */
    @GameTest(maxTicks = 20)
    public void aRiderLeavingMidFloorFailsTheirHaul(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9983;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        log.setHaul(owner.getUUID(), 10);
        log.setHaul(rider.getUUID(), 10);
        int before = banked(log, rider);
        try {
            helper.assertTrue(RunLifecycle.exit(rider, RunLifecycle.ExitReason.COMMAND), "the rider leaves");
            helper.assertValueEqual(log.haulOf(rider.getUUID()), 0, "the haul is settled at once, not at next login");
            helper.assertValueEqual(banked(log, rider) - before, 5, "on a floor it fails: half of 10 banks");
            helper.assertValueEqual(log.haulOf(owner.getUUID()), 10, "the owner's haul is untouched");
            helper.assertTrue(InstanceRegistry.byMember.get(owner.getUUID()) == record, "the run goes on");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** A rider who walks out between floors banks in full, as at the home lever. */
    @GameTest(maxTicks = 20)
    public void aRiderLeavingAtACheckpointBanksInFull(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9984;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.floor.completed.add(owner.getUUID());
        log.setHaul(rider.getUUID(), 10);
        int before = banked(log, rider);
        try {
            RunLifecycle.exit(rider, RunLifecycle.ExitReason.COMMAND);
            helper.assertValueEqual(log.haulOf(rider.getUUID()), 0, "the haul is settled");
            helper.assertValueEqual(banked(log, rider) - before, 10, "all 10 scrap is banked, in full");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** An owner who walks out mid-floor with a party still inside fails the dungeon for everyone. */
    @GameTest(maxTicks = 20)
    public void anOwnerLeavingMidFloorWithAPartyFailsTheDungeon(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9985;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        log.setHaul(owner.getUUID(), 8);
        log.setHaul(rider.getUUID(), 8);
        try {
            RunLifecycle.exit(owner, RunLifecycle.ExitReason.COMMAND);
            helper.assertTrue(InstanceRegistry.bySlot.get(slot) != record, "the dungeon is closed");
            helper.assertValueEqual(log.haulOf(owner.getUUID()), 0, "the owner's haul is settled");
            helper.assertValueEqual(log.haulOf(rider.getUUID()), 0, "so is the rider's");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** A solo owner who stepped out of a run that waits for them is not carrying an orphan haul. */
    @GameTest(maxTicks = 20)
    public void aWaitingRunKeepsItsOwnersHaulAtRisk(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9986;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        InstanceRegistry.byMember.remove(owner.getUUID());
        log.setHaul(owner.getUUID(), 10);
        try {
            helper.assertTrue(RunLifecycle.ownsLiveRun(owner.getUUID()), "the owner has a live run to go back to");
            RunLifecycle.onJoinHaul(server, owner);
            helper.assertValueEqual(log.haulOf(owner.getUUID()), 10, "logging in does not bank a haul that is still at risk");
            record.phase = RunSession.Phase.HOME;
            helper.assertTrue(!RunLifecycle.ownsLiveRun(owner.getUUID()), "a lobby is not a run in progress");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** A rider who loses their connection on a floor fails, like any other way of leaving. */
    @GameTest(maxTicks = 20)
    public void aRiderWhoDropsMidFloorFails(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9987;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        log.setHaul(rider.getUUID(), 10);
        int before = banked(log, rider);
        try {
            RunLifecycle.dropMember(server, record, rider.getUUID(), rider, "member disconnected", true);
            helper.assertValueEqual(log.haulOf(rider.getUUID()), 0, "the haul is gone");
            helper.assertValueEqual(banked(log, rider) - before, 5, "half of 10 banks, the rest is lost");
            helper.assertTrue(!record.members.containsKey(rider.getUUID()), "the rider is out of the party");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** A rider who drops between floors is paid in full. */
    @GameTest(maxTicks = 20)
    public void aRiderWhoDropsAtACheckpointIsPaidInFull(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9988;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.floor.completed.add(owner.getUUID());
        log.setHaul(rider.getUUID(), 10);
        int before = banked(log, rider);
        try {
            RunLifecycle.dropMember(server, record, rider.getUUID(), rider, "member disconnected", true);
            helper.assertValueEqual(banked(log, rider) - before, 10, "banked in full");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** An owner who never comes back on a floor fails the dungeon for everyone. */
    @GameTest(maxTicks = 20)
    public void anOwnerWhoNeverReturnsMidFloorFailsTheDungeon(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int slot = 9989;
        InstanceRecord record = record(helper, server, slot, owner, rider);
        record.members.remove(owner.getUUID());
        InstanceRegistry.byMember.remove(owner.getUUID());
        log.setHaul(owner.getUUID(), 10);
        log.setHaul(rider.getUUID(), 8);
        int ownerBefore = banked(log, owner);
        int riderBefore = banked(log, rider);
        try {
            helper.assertTrue(RunLifecycle.watchOwnerGrace(server, record, Long.MAX_VALUE), "the run ends");
            helper.assertValueEqual(banked(log, owner) - ownerBefore, 5, "the owner fails: half of 10");
            helper.assertValueEqual(log.haulOf(owner.getUUID()), 0, "their haul is gone");
            helper.assertValueEqual(banked(log, rider) - riderBefore, 4, "the rider fails: half of 8");
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** Banked scrap as a single number: the compass's levels at five scrap each, plus the bar. */
    private static int banked(DungeonLog log, ServerPlayer player) {
        DungeonLog.Entry entry = log.get(player.getUUID());
        return entry.highestCharts() * ScrapMath.SCRAP_PER_CHART + entry.chartProgress();
    }

    private static InstanceRecord record(GameTestHelper helper, MinecraftServer server, int slot,
                                         ServerPlayer owner, ServerPlayer rider) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(), layout,
                Set.of(), owner.getUUID(), false);
        record.floor.theme = "pocketdungeons:test_join_from_dungeon";
        record.members.put(owner.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.members.put(rider.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.phase = RunSession.Phase.ACTIVE;
        InstanceRegistry.bySlot.put(slot, record);
        InstanceRegistry.usedSlots.add(slot);
        InstanceRegistry.byMember.put(owner.getUUID(), record);
        InstanceRegistry.byMember.put(rider.getUUID(), record);
        return record;
    }

    private static void unregister(int slot, ServerPlayer... members) {
        InstanceRegistry.bySlot.remove(slot);
        InstanceRegistry.usedSlots.remove(slot);
        for (ServerPlayer member : members) {
            InstanceRegistry.byMember.remove(member.getUUID());
        }
    }

    @SuppressWarnings("removal")
    private static ServerPlayer standInALoadedChunk(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }
}
