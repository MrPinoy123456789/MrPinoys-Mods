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
 * Audit wave 2b scenarios: bank anywhere, per-floor banking, the checkpoint
 * exit and the owner's reconnect grace.
 * <ul>
 *   <li>{@code bankAfterOneFloor}: one cleared floor banks its own door step,
 *       a Greater floor a whole level, a free floor a third of one kept for
 *       later.</li>
 *   <li>{@code bankAfterFourFloors}: four floors in one interval bank four
 *       Greater steps' worth, with the band scaled to four floors.</li>
 *   <li>{@code exitAtCheckpointBanksOneBandWorse}: the owner leaving between
 *       floors settles every member present one band worse, each from their
 *       own key and carry, and ends the run.</li>
 *   <li>{@code ownerRejoiningWithinGraceKeepsTheRun} and
 *       {@code ownerMissingTheGraceEndsTheRun}: a disconnected owner's run
 *       holds for the party, a rejoin lifts the hold, and a missed deadline
 *       ends it (banking one band worse at a checkpoint).</li>
 *   <li>{@code goHomeRefusesAnyoneButTheOwnerBetweenFloors}: the HOME lever's
 *       gates.</li>
 * </ul>
 *
 * <p>What the harness cannot reach: the gametest server has no
 * {@code pocketdungeons:void} (DISCOVERIES trap 18), so the silent homecoming
 * that follows the settlement ({@code completeSafeReturn}) stops at its
 * missing-level guard, and {@code advanceFloor} and {@code commitDoor}
 * (which record the floor's step and apply the depth head start) never run.
 * Each scenario sets the interval those would have left and drives the
 * production method that owns the decision: {@code settleSafeVisit},
 * {@code exit}, {@code dropMember}, {@code rejoinOwnedInstance} and
 * {@code watchOwnerGrace}. The HOME lever's physical walk home, the screen
 * and the bulb are live checks ({@code LIVE_TEST_PASS} section 44).
 *
 * <p>Same package rationale as {@link FloorLoopGameTest}.
 */
public final class BankAnywhereGameTest {

    /** One floor, bank: a Greater floor is a level, a free floor is kept toward one. */
    @GameTest(maxTicks = 20)
    public void bankAfterOneFloor(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int perLevel = PocketDungeonsConfig.floorsPerSafeVisit();

        InstanceRecord greater = clearedRecord(helper, server, 9991, owner, List.of(3), List.of(0));
        log.setKeystone(owner.getUUID(), 5, Set.of());
        log.setKeyProgress(owner.getUUID(), 0);
        RunLifecycle.settleSafeVisit(server, greater);
        IntervalBanking.Settlement expected = IntervalBanking.settle(List.of(3), 0, 0, perLevel, 0, 0);
        if (log.get(owner.getUUID()).keystoneLevel() != 5 + expected.levels()
                || log.get(owner.getUUID()).keyProgress() != expected.progress()) {
            helper.fail("one Greater floor should bank " + expected + ", got level "
                    + log.get(owner.getUUID()).keystoneLevel() + " and progress "
                    + log.get(owner.getUUID()).keyProgress());
            return;
        }

        InstanceRecord free = clearedRecord(helper, server, 9992, owner, List.of(1), List.of(0));
        log.setKeystone(owner.getUUID(), 5, Set.of());
        log.setKeyProgress(owner.getUUID(), 0);
        RunLifecycle.settleSafeVisit(server, free);
        if (perLevel > 1 && (log.get(owner.getUUID()).keystoneLevel() != 5
                || log.get(owner.getUUID()).keyProgress() != 1)) {
            helper.fail("one free floor should bank nothing yet and keep one step, got level "
                    + log.get(owner.getUUID()).keystoneLevel() + " and progress "
                    + log.get(owner.getUUID()).keyProgress());
            return;
        }
        helper.succeed();
    }

    /** Four floors in one interval: four Greater steps, the band over four floors, the kept carry spent. */
    @GameTest(maxTicks = 20)
    public void bankAfterFourFloors(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int perLevel = PocketDungeonsConfig.floorsPerSafeVisit();
        // Floor 4 started with the default zone's head start of 1 omen.
        List<Integer> steps = List.of(3, 3, 3, 3);
        List<Integer> omens = List.of(0, 0, 0, ZoneRules.DEFAULT.baseOmen(4, perLevel));
        InstanceRecord record = clearedRecord(helper, server, 9993, owner, steps, omens);
        log.setKeystone(owner.getUUID(), 20, Set.of());
        log.setKeyProgress(owner.getUUID(), 2);

        RunLifecycle.settleSafeVisit(server, record);
        IntervalBanking.Settlement expected = IntervalBanking.settle(steps,
                omens.stream().mapToInt(Integer::intValue).sum(), 2, perLevel, 0,
                ZoneRules.DEFAULT.bonusChests(steps.size()));
        if (expected.band() != 0) {
            helper.fail("four floors with one omen should settle calm, got band " + expected.band());
            return;
        }
        if (log.get(owner.getUUID()).keystoneLevel() != 20 + expected.levels()
                || log.get(owner.getUUID()).keyProgress() != expected.progress()) {
            helper.fail("four Greater floors and a carry of 2 should bank " + expected + ", got level "
                    + log.get(owner.getUUID()).keystoneLevel() + " and progress "
                    + log.get(owner.getUUID()).keyProgress());
            return;
        }
        if (perLevel == 3 && expected.levels() != 4) {
            helper.fail("with the default interval, 12 steps and a carry of 2 make 4 levels, got " + expected);
            return;
        }
        helper.succeed();
    }

    /** The owner leaving between floors: every member banks one band worse from their own key, and the run ends. */
    @GameTest(maxTicks = 20)
    public void exitAtCheckpointBanksOneBandWorse(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int perLevel = PocketDungeonsConfig.floorsPerSafeVisit();
        int slot = 9994;
        // A calm interval (one omen a floor over three floors); leaving makes it uneasy.
        List<Integer> steps = List.of(3, 3, 3);
        List<Integer> omens = List.of(1, 1, 1);
        InstanceRecord record = clearedRecord(helper, server, slot, owner, steps, omens);
        record.members.put(rider.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        log.setKeystone(owner.getUUID(), 10, Set.of());
        log.setKeyProgress(owner.getUUID(), 0);
        log.setKeystone(rider.getUUID(), 3, Set.of());
        log.setKeyProgress(rider.getUUID(), 2);
        register(record, slot, owner, rider);
        try {
            if (!RunLifecycle.exit(owner, RunLifecycle.ExitReason.COMMAND)) {
                helper.fail("the owner's exit between floors should succeed");
                return;
            }
            IntervalBanking.Settlement ownerExpected = IntervalBanking.settle(steps, 3, 0, perLevel,
                    IntervalBanking.LEAVE_PENALTY, 0);
            IntervalBanking.Settlement riderExpected = IntervalBanking.settle(steps, 3, 2, perLevel,
                    IntervalBanking.LEAVE_PENALTY, 0);
            if (ownerExpected.band() != 1) {
                helper.fail("leaving a calm interval should settle uneasy, got band " + ownerExpected.band());
                return;
            }
            if (log.get(owner.getUUID()).keystoneLevel() != 10 + ownerExpected.levels()
                    || log.get(owner.getUUID()).keyProgress() != ownerExpected.progress()) {
                helper.fail("the owner should bank " + ownerExpected + ", got level "
                        + log.get(owner.getUUID()).keystoneLevel());
                return;
            }
            if (log.get(rider.getUUID()).keystoneLevel() != 3 + riderExpected.levels()
                    || log.get(rider.getUUID()).keyProgress() != riderExpected.progress()) {
                helper.fail("the rider should bank from their own key and carry: " + riderExpected
                        + ", got level " + log.get(rider.getUUID()).keystoneLevel() + " and progress "
                        + log.get(rider.getUUID()).keyProgress());
                return;
            }
            if (InstanceRegistry.bySlot.get(slot) == record || InstanceRegistry.byMember.containsKey(rider.getUUID())) {
                helper.fail("the run should end when the owner leaves at a checkpoint");
                return;
            }
        } finally {
            unregister(slot, owner, rider);
        }

        // A dire interval left early banks nothing and keeps the carry.
        int direSlot = 9995;
        InstanceRecord dire = clearedRecord(helper, server, direSlot, owner, List.of(3, 3, 3), List.of(3, 3, 3));
        log.setKeystone(owner.getUUID(), 10, Set.of());
        log.setKeyProgress(owner.getUUID(), 1);
        register(dire, direSlot, owner);
        try {
            RunLifecycle.exit(owner, RunLifecycle.ExitReason.COMMAND);
            if (log.get(owner.getUUID()).keystoneLevel() != 10 || log.get(owner.getUUID()).keyProgress() != 1) {
                helper.fail("an uneasy interval left early is dire: no levels, carry kept; got level "
                        + log.get(owner.getUUID()).keystoneLevel() + " and progress "
                        + log.get(owner.getUUID()).keyProgress());
                return;
            }
        } finally {
            unregister(direSlot, owner);
        }
        helper.succeed();
    }

    /** R6: the owner drops, the party keeps the run, the owner comes back and the hold lifts. */
    @GameTest(maxTicks = 20)
    public void ownerRejoiningWithinGraceKeepsTheRun(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        int slot = 9996;
        ReturnPoint outside = new ReturnPoint(Level.OVERWORLD, new Vec3(4.5, 70.0, 4.5), 0.0f, 0.0f);
        InstanceRecord record = clearedRecord(helper, server, slot, owner, List.of(2), List.of(0));
        record.members.put(owner.getUUID(), outside);
        record.members.put(rider.getUUID(), outside);
        record.stagingCellOrigin = helper.absolutePos(new BlockPos(0, 1, RoomGeometry.CELL));
        register(record, slot, owner, rider);
        try {
            RunLifecycle.dropMember(server, record, owner.getUUID(), owner, "member disconnected", true);
            if (PocketDungeonsConfig.ownerReconnectGraceSeconds() <= 0) {
                helper.succeed(); // grace switched off in this run's config: nothing to hold
                return;
            }
            if (InstanceRegistry.bySlot.get(slot) != record || !record.members.containsKey(rider.getUUID())) {
                helper.fail("an owner's disconnect should hold the run for the party still inside");
                return;
            }
            if (record.ownerAbsentUntilTick <= server.overworld().getGameTime()) {
                helper.fail("the hold should carry a deadline in the future, got " + record.ownerAbsentUntilTick);
                return;
            }
            if (RunLifecycle.watchOwnerGrace(server, record, server.overworld().getGameTime())) {
                helper.fail("the watcher ended the run before the deadline");
                return;
            }
            if (!Instances.rejoinOwnedInstance(server, owner, outside)) {
                helper.fail("the owner was not re-admitted within the grace");
                return;
            }
            if (record.ownerAbsentUntilTick != 0 || InstanceRegistry.byMember.get(owner.getUUID()) != record) {
                helper.fail("the rejoin should lift the hold and restore the owner");
                return;
            }
            if (record.phase != RunSession.Phase.FLOOR_CLEARED || !record.interval.floorSteps.equals(List.of(2))) {
                helper.fail("the interval should survive the owner's absence: phase " + record.phase
                        + ", steps " + record.interval.floorSteps);
                return;
            }
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    /** R6: the deadline passes; at a checkpoint the party banks one band worse, mid-floor it just ends. */
    @GameTest(maxTicks = 20)
    public void ownerMissingTheGraceEndsTheRun(GameTestHelper helper) {
        if (PocketDungeonsConfig.ownerReconnectGraceSeconds() <= 0) {
            helper.succeed();
            return;
        }
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        int perLevel = PocketDungeonsConfig.floorsPerSafeVisit();

        int slot = 9997;
        InstanceRecord checkpoint = clearedRecord(helper, server, slot, owner, List.of(3, 3, 3), List.of(0, 0, 0));
        checkpoint.members.put(rider.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        log.setKeystone(rider.getUUID(), 7, Set.of());
        log.setKeyProgress(rider.getUUID(), 0);
        register(checkpoint, slot, owner, rider);
        try {
            RunLifecycle.dropMember(server, checkpoint, owner.getUUID(), owner, "member disconnected", true);
            if (!RunLifecycle.watchOwnerGrace(server, checkpoint, checkpoint.ownerAbsentUntilTick)) {
                helper.fail("the run should end once the deadline passes");
                return;
            }
            IntervalBanking.Settlement expected = IntervalBanking.settle(List.of(3, 3, 3), 0, 0, perLevel,
                    IntervalBanking.LEAVE_PENALTY, 0);
            if (log.get(rider.getUUID()).keystoneLevel() != 7 + expected.levels()) {
                helper.fail("the rider should bank one band worse at the checkpoint: " + expected
                        + ", got level " + log.get(rider.getUUID()).keystoneLevel());
                return;
            }
            if (InstanceRegistry.bySlot.get(slot) == checkpoint) {
                helper.fail("the run should be gone");
                return;
            }
        } finally {
            unregister(slot, owner, rider);
        }

        int floorSlot = 9998;
        InstanceRecord midFloor = clearedRecord(helper, server, floorSlot, owner, List.of(), List.of());
        midFloor.phase = RunSession.Phase.ACTIVE;
        midFloor.floor.completed.clear();
        midFloor.interval.floorIndex = 0;
        midFloor.members.put(rider.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        int riderLevel = log.get(rider.getUUID()).keystoneLevel();
        register(midFloor, floorSlot, owner, rider);
        try {
            RunLifecycle.dropMember(server, midFloor, owner.getUUID(), owner, "member disconnected", true);
            if (InstanceRegistry.bySlot.get(floorSlot) != midFloor) {
                helper.fail("a mid-floor disconnect should hold the run too");
                return;
            }
            if (!RunLifecycle.watchOwnerGrace(server, midFloor, midFloor.ownerAbsentUntilTick)) {
                helper.fail("the mid-floor run should end at the deadline");
                return;
            }
            if (log.get(rider.getUUID()).keystoneLevel() != riderLevel) {
                helper.fail("a run ended mid-floor banks nothing");
                return;
            }
        } finally {
            unregister(floorSlot, owner, rider);
        }

        // A voluntary exit mid-floor with the party inside still ends the run at once.
        int exitSlot = 9999;
        InstanceRecord leaving = clearedRecord(helper, server, exitSlot, owner, List.of(), List.of());
        leaving.phase = RunSession.Phase.ACTIVE;
        leaving.floor.completed.clear();
        leaving.interval.floorIndex = 0;
        leaving.members.put(rider.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        register(leaving, exitSlot, owner, rider);
        try {
            RunLifecycle.exit(owner, RunLifecycle.ExitReason.COMMAND);
            if (InstanceRegistry.bySlot.get(exitSlot) == leaving || leaving.ownerAbsentUntilTick != 0) {
                helper.fail("a voluntary exit is not a disconnect: the run ends at once");
                return;
            }
        } finally {
            unregister(exitSlot, owner, rider);
        }
        helper.succeed();
    }

    /** The HOME lever and /dungeon cashout: owner only, between floors only. */
    @GameTest(maxTicks = 20)
    public void goHomeRefusesAnyoneButTheOwnerBetweenFloors(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        ServerPlayer rider = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        int slot = 9990;
        InstanceRecord record = clearedRecord(helper, server, slot, owner, List.of(1), List.of(0));
        record.members.put(rider.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        register(record, slot, owner, rider);
        try {
            if (RunLifecycle.goHome(rider)) {
                helper.fail("a member who is not the owner went home");
                return;
            }
            record.phase = RunSession.Phase.ACTIVE;
            if (RunLifecycle.goHome(owner)) {
                helper.fail("the way home opened mid-floor");
                return;
            }
            record.phase = RunSession.Phase.FLOOR_CLEARED;
            // Between floors the owner is let through; the homecoming then
            // stops at the missing dungeon level and puts the lever back.
            RunLifecycle.goHome(owner);
            if (record.phase != RunSession.Phase.FLOOR_CLEARED) {
                helper.fail("a homecoming that cannot finish should leave the run between floors, got "
                        + record.phase);
                return;
            }
        } finally {
            unregister(slot, owner, rider);
        }
        helper.succeed();
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * A one-cell keystone floor, cleared by {@code owner}, standing between
     * floors with the interval {@code advanceFloor} would have left:
     * {@code steps} and {@code omens}, one entry per cleared floor.
     */
    private static InstanceRecord clearedRecord(GameTestHelper helper, MinecraftServer server, int slot,
                                                ServerPlayer owner, List<Integer> steps, List<Integer> omens) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(), layout,
                Set.of(), owner.getUUID(), false);
        record.floor.theme = "pocketdungeons:test_bank_anywhere";
        record.members.put(owner.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.floor.completed.add(owner.getUUID());
        record.floor.chosenStep = steps.isEmpty() ? 1 : steps.get(steps.size() - 1);
        record.interval.floorSteps.addAll(steps);
        record.interval.floorOmens.addAll(omens);
        record.interval.floorIndex = steps.size();
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
