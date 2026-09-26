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
import java.util.UUID;

/**
 * The three interval scenarios from the 2026-09 audit (section 7), pinned
 * before any lifecycle refactor:
 * <ul>
 *   <li>{@code consecutiveSafeVisitsLandInTheSameBand} (B1): two safe visits
 *       back to back with the same non-zero omen per floor finish in the same
 *       band.</li>
 *   <li>{@code everyMemberOnThePadIsCredited} (B2): a second member touching
 *       the pad after the floor has advanced is credited and gets a run
 *       record at the safe visit, without a second advance.</li>
 *   <li>{@code ownerRejoiningBetweenFloorsKeepsTheInterval} (B3): an owner who
 *       disconnects in {@code FLOOR_CLEARED} keeps the instance, is re-admitted
 *       on rejoin with their return point intact, and is placed in the
 *       staging room.</li>
 * </ul>
 * Alongside them, {@code omenBarFollowsThePhase} pins when the omen bar
 * shows (wave 2a).
 *
 * <p>What the harness cannot reach: the gametest server has no
 * {@code pocketdungeons:void} (DISCOVERIES trap 18), and {@code RunLifecycle}
 * and {@code Instances} read that dimension directly. So {@code advanceFloor}
 * and the stamping half of {@code returnToSafe} never run here, and a
 * teleport into the dungeon falls back to world spawn. Each scenario drives
 * the production method that owns the decision ({@code bankFloorOmen} and
 * {@code beginInterval}, {@code completeRun} and {@code settleSafeVisit},
 * {@code dropMember}, {@code rejoinOwnedInstance} and {@code admitPosition})
 * and sets the phase {@code advanceFloor} would have set by hand. The
 * physical landing spot and the homecoming stamp are live checks
 * ({@code LIVE_TEST_PASS}, audit wave 1).
 *
 * <p>Same package rationale as {@link FloorLoopGameTest}.
 */
public final class FloorIntervalGameTest {

    private static final int FLOORS_PER_VISIT = 3;
    private static final int OMEN_PER_FLOOR = 2;

    /** B1: the homecoming reset leaves nothing of the last interval's omen behind. */
    @GameTest(maxTicks = 20)
    public void consecutiveSafeVisitsLandInTheSameBand(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        InstanceRecord record = new InstanceRecord(9981, helper.absolutePos(BlockPos.ZERO),
                server.getTickCount(), null, Set.of(), UUID.randomUUID(), false);
        int[] bands = new int[2];
        for (int visit = 0; visit < 2; visit++) {
            int band = -1;
            for (int floor = 0; floor < FLOORS_PER_VISIT; floor++) {
                record.interval.omen = OMEN_PER_FLOOR;
                band = RunLifecycle.bankFloorOmen(record, FLOORS_PER_VISIT);
            }
            bands[visit] = band;
            // The settlement guard is part of the interval too.
            record.interval.safeVisitSettled = true;
            // What returnToSafe, its teleport fallback and the quit reset
            // call as the interval ends.
            record.beginInterval(Instances.lobbyLayout(record.origin));
            if (!record.interval.floorOmens.isEmpty() || record.interval.omen != 0 || record.interval.safeVisitSettled) {
                helper.fail("beginInterval left interval state behind: floorOmens="
                        + record.interval.floorOmens + ", omen=" + record.interval.omen
                        + ", settled=" + record.interval.safeVisitSettled);
                return;
            }
        }
        if (bands[0] != bands[1]) {
            helper.fail("Visit 2 finished in band " + bands[1] + " but visit 1 in band " + bands[0]
                    + " with the same omen per floor");
            return;
        }
        if (bands[0] == 0) {
            helper.fail("Fixture should land above band 0 to prove anything; got band 0");
            return;
        }
        helper.succeed();
    }

    /**
     * R3's companion: a safe return that cannot finish (here, the dungeon
     * level is missing, as it always is on this server) puts the run back in
     * {@code FLOOR_CLEARED} so the lever still works. The transition table
     * has no direct SAFE_RETURN to FLOOR_CLEARED edge, which used to leave
     * this stuck in SAFE_RETURN.
     */
    @GameTest(maxTicks = 20)
    public void unfinishedSafeReturnLeavesTheLeverWorking(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        InstanceRecord record = floorRecord(helper, server, 9982, owner.getUUID());
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.floor.completed.add(owner.getUUID());
        record.floor.safeStaging = true;
        InstanceRegistry.byMember.put(owner.getUUID(), record);
        try {
            if (RunLifecycle.returnToSafe(owner)) {
                helper.fail("returnToSafe should not succeed without the dungeon level");
                return;
            }
            if (record.phase != RunSession.Phase.FLOOR_CLEARED) {
                helper.fail("phase should be back at FLOOR_CLEARED, got " + record.phase);
                return;
            }
            if (!RunSession.canChooseDoor(record)) {
                helper.fail("the staging room's doors should still accept a choice");
                return;
            }
        } finally {
            InstanceRegistry.byMember.remove(owner.getUUID());
        }
        helper.succeed();
    }

    /** B2: the second member onto the pad is credited without a second advance. */
    @GameTest(maxTicks = 20)
    public void everyMemberOnThePadIsCredited(GameTestHelper helper) {
        ServerPlayer first = standInALoadedChunk(helper);
        ServerPlayer second = standInALoadedChunk(helper);
        MinecraftServer server = first.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        InstanceRecord record = floorRecord(helper, server, 9983, first.getUUID());
        ReturnPoint outside = new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f);
        record.members.put(first.getUUID(), outside);
        record.members.put(second.getUUID(), outside);
        record.phase = RunSession.Phase.ACTIVE;
        record.floor.chosenStep = 0; // no keystone grant in settleSafeVisit
        record.floor.rewardChests = 1;

        int firstRuns = log.get(first.getUUID()).runsCompleted();
        int secondRuns = log.get(second.getUUID()).runsCompleted();
        int secondRecords = log.runRecordsOf(second.getUUID()).size();

        // The first touch. advanceFloor returns early here (no staging room
        // on this server), so set the phase it would have left behind.
        RunLifecycle.completeRun(server, record, first);
        if (!record.floor.completed.contains(first.getUUID())) {
            helper.fail("the first member should be credited");
            return;
        }
        RunSession.transition(record, RunSession.Phase.FLOOR_CLEARED);
        record.interval.floorIndex = 1;

        // The second touch, after the floor has advanced.
        RunLifecycle.completeRun(server, record, second);
        if (!record.floor.completed.contains(second.getUUID())) {
            helper.fail("the second member onto the pad was not credited");
            return;
        }
        if (record.phase != RunSession.Phase.FLOOR_CLEARED || record.interval.floorIndex != 1) {
            helper.fail("the second touch advanced the floor again: phase " + record.phase
                    + ", floorIndex " + record.interval.floorIndex);
            return;
        }
        if (log.get(first.getUUID()).runsCompleted() != firstRuns + 1
                || log.get(second.getUUID()).runsCompleted() != secondRuns + 1) {
            helper.fail("each member should have exactly one more completion recorded");
            return;
        }

        // A third contact by the same member credits nothing more.
        RunLifecycle.completeRun(server, record, second);
        if (log.get(second.getUUID()).runsCompleted() != secondRuns + 1) {
            helper.fail("a repeat contact recorded a second completion");
            return;
        }

        // The safe visit keeps a run record for every credited member.
        RunLifecycle.settleSafeVisit(server, record);
        if (log.runRecordsOf(second.getUUID()).size() != secondRecords + 1) {
            helper.fail("the second member got no run record at the safe visit");
            return;
        }
        helper.succeed();
    }

    /** B3: disconnecting between floors keeps the interval, and rejoining lands in staging. */
    @GameTest(maxTicks = 20)
    public void ownerRejoiningBetweenFloorsKeepsTheInterval(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        int slot = 9984;
        InstanceRecord record = floorRecord(helper, server, slot, owner.getUUID());
        ReturnPoint outside = new ReturnPoint(Level.OVERWORLD, new Vec3(12.5, 70.0, -4.5), 90.0f, 0.0f);
        BlockPos staging = helper.absolutePos(new BlockPos(0, 1, RoomGeometry.CELL));
        record.members.put(owner.getUUID(), outside);
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.floor.completed.add(owner.getUUID());
        record.floor.chosenStep = 1;
        record.interval.floorIndex = 1;
        record.interval.floorOmens.add(OMEN_PER_FLOOR);
        record.stagingCellOrigin = staging;
        InstanceRegistry.bySlot.put(slot, record);
        InstanceRegistry.byMember.put(owner.getUUID(), record);
        InstanceRegistry.usedSlots.add(slot);
        try {
            // The disconnect path (handleDisconnect's body, minus the return
            // point it parks for the JOIN handler).
            RunLifecycle.dropMember(server, record, owner.getUUID(), owner, "member disconnected");
            if (InstanceRegistry.bySlot.get(slot) != record) {
                helper.fail("disconnecting between floors closed the instance");
                return;
            }
            if (!RunLifecycle.isReenterable(record)) {
                helper.fail("a FLOOR_CLEARED instance should be reenterable");
                return;
            }

            // The JOIN recovery path.
            if (!Instances.rejoinOwnedInstance(server, owner, outside)) {
                helper.fail("the owner was not re-admitted on rejoin");
                return;
            }
            if (InstanceRegistry.byMember.get(owner.getUUID()) != record) {
                helper.fail("the owner is not a member of their run after rejoining");
                return;
            }
            if (!outside.equals(record.members.get(owner.getUUID()))) {
                helper.fail("rejoining replaced the return point: " + record.members.get(owner.getUUID()));
                return;
            }
            if (record.phase != RunSession.Phase.FLOOR_CLEARED
                    || !record.floor.completed.contains(owner.getUUID())
                    || !record.interval.floorOmens.equals(List.of(OMEN_PER_FLOOR))
                    || record.interval.floorIndex != 1) {
                helper.fail("the interval did not survive the rejoin: phase " + record.phase
                        + ", completed " + record.floor.completed + ", floorOmens " + record.interval.floorOmens
                        + ", floorIndex " + record.interval.floorIndex);
                return;
            }
            Vec3 expected = Vec3.atBottomCenterOf(staging.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2));
            if (!expected.equals(Instances.admitPosition(record))) {
                helper.fail("admit should land in the staging room centre " + expected
                        + ", not " + Instances.admitPosition(record));
                return;
            }
        } finally {
            InstanceRegistry.bySlot.remove(slot);
            InstanceRegistry.byMember.remove(owner.getUUID());
            InstanceRegistry.usedSlots.remove(slot);
        }
        helper.succeed();
    }

    /**
     * The omen bar shows during a floor and between floors, never at home or
     * on a record outside the floor loop, and is let go at teardown. Who
     * watches it is a live check: the mock players here never stand in the
     * dungeon dimension.
     */
    @GameTest(maxTicks = 20)
    public void omenBarFollowsThePhase(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        InstanceRecord record = floorRecord(helper, server, 9985, UUID.randomUUID());
        record.phase = RunSession.Phase.HOME;
        OmenBar.sync(server, record);
        if (OmenBar.shows(record) || record.omenBar != null) {
            helper.fail("the bar should stay hidden, and uncreated, at home");
            return;
        }
        record.phase = RunSession.Phase.ACTIVE;
        record.interval.omen = 2;
        OmenBar.sync(server, record);
        if (!OmenBar.shows(record) || record.omenBar == null) {
            helper.fail("the bar should show during a floor");
            return;
        }
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.interval.floorIndex = 1;
        if (!OmenBar.shows(record)) {
            helper.fail("the bar should show between floors once a floor is cleared");
            return;
        }
        record.phase = RunSession.Phase.PREVIEW;
        record.interval.floorIndex = 0;
        if (OmenBar.shows(record)) {
            helper.fail("a preview opened from home has no floor to report");
            return;
        }
        OmenBar.close(record);
        if (record.omenBar != null) {
            helper.fail("teardown should let the bar go");
            return;
        }
        InstanceRecord untimed = new InstanceRecord(9986, record.origin, server.getTickCount(),
                record.layout, Set.of(), UUID.randomUUID(), true);
        untimed.phase = RunSession.Phase.ACTIVE;
        OmenBar.omenRose(server, untimed, Omen.Source.SHRIEK, 1);
        if (OmenBar.shows(untimed) || untimed.omenBar != null) {
            helper.fail("an admin untimed run is outside the loop and gets no bar");
            return;
        }
        helper.succeed();
    }

    // ---- helpers ---------------------------------------------------------

    /** A one-cell keystone floor with no spawners, so the completion gate passes. */
    private static InstanceRecord floorRecord(GameTestHelper helper, MinecraftServer server,
                                              int slot, UUID owner) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(), layout,
                Set.of(), owner, false);
        record.floor.theme = "pocketdungeons:test_interval";
        return record;
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
