package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.EnumSet;
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
        testOutsideEveryDungeonCell();
        testRoomCellIsSkipped();
        testProtectionLiftsAfterCompletion();
        testAdminBuildSingleCell();
        InstanceRegistry.bySlot.clear();
        System.out.println("DungeonShellProtectionTest passed");
    }

    /** A two-cell run, still active: both cells are protected. */
    private static void testInsideDungeonCellDuringActiveRun() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin,
                List.of(new PlanCell(0, 0), new PlanCell(1, 0)));
        InstanceRecord record = new InstanceRecord(90, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                EnumSet.noneOf(Affix.class), null, false);
        InstanceRegistry.bySlot.put(90, record);

        check(Instances.dungeonRecordAt(origin.offset(5, 2, 5)) == record);
        check(Instances.dungeonRecordAt(origin.offset(20, 2, 5)) == record);
    }

    /** Outside every occupied cell, and above/below the 16x16x7 box, is never protected. */
    private static void testOutsideEveryDungeonCell() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceRecord record = new InstanceRecord(91, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                EnumSet.noneOf(Affix.class), null, false);
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
                EnumSet.noneOf(Affix.class), UUID.randomUUID(), false);
        record.roomCellOrigin = geometry.cellOrigin(new PlanCell(0, 0));
        InstanceRegistry.bySlot.put(92, record);

        check(Instances.dungeonRecordAt(origin.offset(5, 2, 5)) == null); // the room cell
        check(Instances.dungeonRecordAt(origin.offset(20, 2, 5)) == record); // the quarry cell
    }

    /** The first completion clears protection for every cell of that run, permanently. */
    private static void testProtectionLiftsAfterCompletion() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceRecord record = new InstanceRecord(93, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                EnumSet.noneOf(Affix.class), null, false);
        InstanceRegistry.bySlot.put(93, record);

        check(Instances.dungeonRecordAt(origin.offset(5, 2, 5)) == record);
        record.completed.add(UUID.randomUUID());
        check(Instances.dungeonRecordAt(origin.offset(5, 2, 5)) == null);
    }

    /** {@code /dungeon admin build} (M31 constraint): same lookup, no room cell to skip. */
    private static void testAdminBuildSingleCell() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(1000, 64, 2000);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceRecord record = new InstanceRecord(94, origin, 0L,
                InstanceLayout.forClearingOnly(origin, geometry),
                EnumSet.noneOf(Affix.class), null, false);
        InstanceRegistry.bySlot.put(94, record);

        check(Instances.dungeonRecordAt(origin.offset(8, 3, 8)) == record); // wall break denied
        record.completed.add(UUID.randomUUID());
        check(Instances.dungeonRecordAt(origin.offset(8, 3, 8)) == null); // wall break allowed
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }
}
