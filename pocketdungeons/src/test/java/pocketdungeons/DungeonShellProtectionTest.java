package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.List;
import java.util.UUID;

/**
 * Pure regression for M31 9.2's dungeon-cell shell protection:
 * {@link Instances#dungeonRecordAt}, the lookup {@link RoomProtection}'s
 * block-break gate and {@link RitualListener}'s placement gate both delegate
 * to. Runs headless like {@link RoomShellTest}: builds an
 * {@code InstanceRecord} by hand with {@link InstanceLayout#forClearingOnly}
 * and drops it straight into {@link InstanceRegistry#bySlot}, no server
 * needed.
 */
public class DungeonShellProtectionTest {

    public static void main(String[] args) {
        testInsideDungeonCellDuringActiveRun();
        testShellProtectedInteriorOpen();
        testOutsideEveryDungeonCell();
        testRoomCellIsSkipped();
        testProtectionLiftsAfterCompletion();
        testAdminBuildSingleCell();
        testInteriorBreaksOnlyNodes();
        InstanceRegistry.bySlot.clear();
        System.out.println("DungeonShellProtectionTest passed");
    }

    /** A two-cell run, still active: both cells' shells are protected. */
    private static void testInsideDungeonCellDuringActiveRun() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin,
                List.of(new PlanCell(0, 0), new PlanCell(1, 0)));
        InstanceRecord record = new InstanceRecord(90, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                Set.of(), null, false);
        InstanceRegistry.bySlot.put(90, record);

        check(Instances.dungeonRecordAt(origin.offset(5, 2, 5)) == record);
        check(Instances.dungeonRecordAt(origin.offset(20, 2, 5)) == record);
    }

    /**
     * The shell (floor, walls, ceiling) of an active dungeon cell is protected;
     * the interior is not. Same coordinate test as the player room's
     * {@link RoomProtection#isShell}.
     */
    private static void testShellProtectedInteriorOpen() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceRecord record = new InstanceRecord(95, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                Set.of(), null, false);
        InstanceRegistry.bySlot.put(95, record);

        BlockPos cellOrigin = Instances.dungeonCellOriginAt(origin.offset(8, 3, 8));
        check(cellOrigin != null, "interior position has a cell origin");

        // Shell positions are protected.
        check(RoomProtection.isShell(origin.offset(0, 3, 8), cellOrigin),
                "wall at x=0 is shell");
        check(RoomProtection.isShell(origin.offset(15, 3, 8), cellOrigin),
                "wall at x=15 is shell");
        check(RoomProtection.isShell(origin.offset(8, 0, 8), cellOrigin),
                "floor at y=0 is shell");
        check(RoomProtection.isShell(origin.offset(8, 6, 8), cellOrigin),
                "ceiling at y=6 is shell");

        // Interior positions are not protected.
        check(!RoomProtection.isShell(origin.offset(8, 3, 8), cellOrigin),
                "interior at 8,3,8 is not shell");
        check(!RoomProtection.isShell(origin.offset(1, 1, 1), cellOrigin),
                "interior at 1,1,1 is not shell");
        check(!RoomProtection.isShell(origin.offset(14, 5, 14), cellOrigin),
                "interior at 14,5,14 is not shell");
    }

    /** Outside every occupied cell, and above/below the 16x16x7 box, is never protected. */
    private static void testOutsideEveryDungeonCell() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceRecord record = new InstanceRecord(91, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                Set.of(), null, false);
        InstanceRegistry.bySlot.put(91, record);

        check(Instances.dungeonRecordAt(origin.offset(50, 2, 50)) == null); // unoccupied cell
        check(Instances.dungeonRecordAt(origin.offset(5, 20, 5)) == null); // above the ceiling
        check(Instances.dungeonRecordAt(origin.offset(5, -1, 5)) == null); // below the floor
    }

    /** The room cell is roomOwnerAt's job; dungeonRecordAt skips it so the two never overlap. */
    private static void testRoomCellIsSkipped() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin,
                List.of(new PlanCell(0, 0), new PlanCell(1, 0)));
        InstanceRecord record = new InstanceRecord(92, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                Set.of(), UUID.randomUUID(), false);
        record.roomCellOrigin = geometry.cellOrigin(new PlanCell(0, 0));
        InstanceRegistry.bySlot.put(92, record);

        check(Instances.dungeonRecordAt(origin.offset(5, 2, 5)) == null); // the room cell
        check(Instances.dungeonRecordAt(origin.offset(20, 2, 5)) == record); // the quarry cell
    }

    /** Shell protection stays up for the lifetime of the run, even after completion. */
    private static void testProtectionLiftsAfterCompletion() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceRecord record = new InstanceRecord(93, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                Set.of(), null, false);
        InstanceRegistry.bySlot.put(93, record);

        check(Instances.dungeonRecordAt(origin.offset(5, 2, 5)) == record);
        record.floor.completed.add(UUID.randomUUID());
        check(Instances.dungeonRecordAt(origin.offset(5, 2, 5)) == record,
                "shell protection stays up after completion");
    }

    /**
     * Dungeon structure W4 (D20), which intentionally changes what "interior open"
     * means: the interior is no longer shell, but only a registered resource node
     * (or the player's own block, or a soft mechanic block) breaks. Everything else
     * in the interior is decoration. The shell test above is unchanged; this pins
     * the position lookup the break rule reads and the decision it feeds.
     */
    private static void testInteriorBreaksOnlyNodes() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        BlockPos node = origin.offset(5, 1, 5);
        BlockPos gate = origin.offset(14, 1, 7);
        BlockPos decoration = origin.offset(8, 2, 8);
        BlockPos shellNode = origin.offset(0, 2, 8); // never registered: a node cannot sit in the shell
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin, 0.0f, origin,
                geometry.bounds(), 0L, 1, 1, 1, true, Set.of(), 0, origin, 0, 0, Set.of(), null, Set.of(),
                Set.of(node), Set.of(gate));
        InstanceRecord record = new InstanceRecord(96, origin, 0L, layout, Set.of(), null, false);
        InstanceRegistry.bySlot.put(96, record);

        BlockPos cellOrigin = Instances.dungeonCellOriginAt(node);
        check(cellOrigin != null, "the node is inside a dungeon cell");
        check(!RoomProtection.isShell(node, cellOrigin), "the node is interior, not shell");
        check(RoomProtection.isShell(shellNode, cellOrigin), "the wall is shell");

        InstanceRecord at = Instances.dungeonRecordAt(node);
        check(at != null && at.floor.nodes.contains(node), "the record knows the node");
        check(!at.floor.nodes.contains(decoration), "decoration is not a node");
        check(at.floor.softBreakables.contains(gate), "the gate is a soft breakable");

        // The decision the break handler makes with those lookups.
        check(BreakRule.decide(false, false, at.floor.nodes.contains(node), false, true).allowed(),
                "a node breaks with the right tool");
        check(!BreakRule.decide(false, false, at.floor.nodes.contains(node), false, false).allowed(),
                "a node does not break with the wrong tool");
        check(!BreakRule.decide(false, false, at.floor.nodes.contains(decoration), false, true).allowed(),
                "decoration does not break even with the right tool");
        check(BreakRule.decide(false, false, false, at.floor.softBreakables.contains(gate), true).allowed(),
                "a soft gate breaks with its tool");
        check(BreakRule.decide(false, true, false, false, false).allowed(),
                "the player's own block breaks by hand");
        check(!BreakRule.decide(true, true, true, true, true).allowed(), "an Ordeal fixture never breaks");

        // Mining a node takes it out of the set, so a later block there is just a block.
        at.floor.nodes.remove(node);
        check(!at.floor.nodes.contains(node), "a mined node is gone");
    }

    /** {@code /dungeon admin build} (M31 constraint): same lookup, no room cell to skip. */
    private static void testAdminBuildSingleCell() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceRecord record = new InstanceRecord(94, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                Set.of(), null, false);
        InstanceRegistry.bySlot.put(94, record);

        // Shell position is protected while the run is active.
        check(Instances.dungeonCellOriginAt(origin.offset(0, 3, 8)) != null,
                "shell position has a cell origin while active");
        record.floor.completed.add(UUID.randomUUID());
        check(Instances.dungeonCellOriginAt(origin.offset(0, 3, 8)) != null,
                "shell position still has a cell origin after completion");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
