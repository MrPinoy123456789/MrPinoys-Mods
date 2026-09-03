package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Covers {@link InstanceRecord}'s child-instance shape and {@link
 * InstanceRegistry#allocateSlotNear} (M25's slot-adjacency contract this
 * file was originally written to pin down), plus (M44.6) {@link Pocket2}'s
 * own pure geometry: {@link Pocket2#childFor}, {@link Pocket2#doorWall},
 * {@link Pocket2#returnPos}, {@link Pocket2#returnYaw}, and {@link
 * Pocket2#isDoorBlock}. {@code openChild}/{@code tickChild}/{@code
 * dieInChild} are not covered: each needs a real {@code ServerLevel} and
 * {@code ServerPlayer} this headless suite does not have.
 */
public class Pocket2Test {

    public static void main(String[] args) {
        testChildRecordRoundTrip();
        testChildNotKeystoneRun();
        testAllocateSlotNear();
        testChildForFindsTheRightParent();
        testDoorWallForEachWall();
        testReturnPosAndYawForEachWall();
        testIsDoorBlockMatchesTheDoorPairOnly();
        System.out.println("Pocket2Test passed");
    }

    /** A child links to its parent by slot and carries the door return position and deadline. */
    private static void testChildRecordRoundTrip() {
        BlockPos origin = new BlockPos(0, 64, 0);
        InstanceLayout layout = Instances.lobbyLayout(origin);
        UUID owner = UUID.randomUUID();
        InstanceRecord parent = new InstanceRecord(7, origin, 1000L, layout,
                EnumSet.noneOf(Affix.class), owner, false);
        BlockPos door = new BlockPos(8, 65, 0);
        InstanceRecord child = new InstanceRecord(8, origin.offset(2048, 0, 0), 2000L, layout,
                EnumSet.noneOf(Affix.class), owner, false, false, 7, door);
        check(child.parentSlot, 7);
        check(child.isChild());
        check(!parent.isChild());
        check(child.returnPos, door);
        check(child.deadlineTick, 0L);
        child.deadlineTick = 2000L + 60L * 20L;
        check(child.deadlineTick, 2000L + 60L * 20L);
        check(child.slot, 8);
    }

    /** A child carries no keystone: it has no spawner gate and no completion pad. */
    private static void testChildNotKeystoneRun() {
        BlockPos origin = new BlockPos(0, 64, 0);
        InstanceLayout layout = Instances.lobbyLayout(origin);
        InstanceRecord child = new InstanceRecord(3, origin, 0L, layout,
                EnumSet.noneOf(Affix.class), null, false, false, 2, origin);
        check(!child.isKeystoneRun());
        check(child.layout.keystoneLevel(), 0);
    }

    /** A child slot lands next to its parent when free, and falls back to the free list. */
    private static void testAllocateSlotNear() {
        InstanceRegistry.usedSlots.clear();
        InstanceRegistry.bySlot.clear();
        InstanceRegistry.byMember.clear();
        int anchor = 10;
        InstanceRegistry.usedSlots.add(anchor);
        int near = InstanceRegistry.allocateSlotNear(anchor);
        check(near, 11);
        InstanceRegistry.usedSlots.add(11);
        int other = InstanceRegistry.allocateSlotNear(anchor);
        check(other, 9);
        InstanceRegistry.usedSlots.add(9);
        InstanceRegistry.usedSlots.add(10);
        // Both neighbours taken: the free list answers.
        int fallback = InstanceRegistry.allocateSlotNear(anchor);
        check(fallback, 0);
        InstanceRegistry.usedSlots.clear();
    }

    /** {@link Pocket2#childFor} finds the one live child of a given parent slot, and only that one. */
    private static void testChildForFindsTheRightParent() {
        InstanceRegistry.bySlot.clear();
        BlockPos origin = new BlockPos(0, 64, 0);
        InstanceLayout layout = Instances.lobbyLayout(origin);
        InstanceRecord unrelated = new InstanceRecord(20, origin, 0L, layout,
                EnumSet.noneOf(Affix.class), null, false, false, 21, origin);
        InstanceRecord child = new InstanceRecord(22, origin, 0L, layout,
                EnumSet.noneOf(Affix.class), null, false, false, 23, origin);
        InstanceRegistry.bySlot.put(20, unrelated);
        InstanceRegistry.bySlot.put(22, child);

        check(Pocket2.childFor(23) == child, "childFor finds the child of its own parent slot");
        check(Pocket2.childFor(21) == unrelated, "childFor does not confuse a different parent's child");
        check(Pocket2.childFor(99) == null, "childFor is null when no child has that parent slot");
        InstanceRegistry.bySlot.clear();
    }

    private static PlanGeometry singleCellGeometry(BlockPos origin) {
        return PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
    }

    /** {@link Pocket2#doorWall} reads the wall a door slot sits on back off its own position. */
    private static void testDoorWallForEachWall() {
        BlockPos origin = new BlockPos(3000, 64, 4000);
        PlanGeometry geometry = singleCellGeometry(origin);

        check(Pocket2.doorWall(geometry, origin.offset(7, 1, 0)) == DoorMask.Direction.NORTH,
                "z at the cell's north edge is the NORTH wall");
        check(Pocket2.doorWall(geometry, origin.offset(7, 1, RoomGeometry.CELL - 1)) == DoorMask.Direction.SOUTH,
                "z at the cell's south edge is the SOUTH wall");
        check(Pocket2.doorWall(geometry, origin.offset(0, 1, 7)) == DoorMask.Direction.WEST,
                "x at the cell's west edge is the WEST wall");
        check(Pocket2.doorWall(geometry, origin.offset(RoomGeometry.CELL - 1, 1, 7)) == DoorMask.Direction.EAST,
                "x at the cell's east edge is the EAST wall");
    }

    /** {@link Pocket2#returnPos} stands the returning member just inside the door's wall; {@link Pocket2#returnYaw} faces them back into the room. */
    private static void testReturnPosAndYawForEachWall() {
        BlockPos origin = new BlockPos(5000, 64, 6000);
        PlanGeometry geometry = singleCellGeometry(origin);
        InstanceLayout layout = InstanceLayout.forClearingOnly(origin, geometry);
        InstanceRecord parent = new InstanceRecord(30, origin, 0L, layout,
                EnumSet.noneOf(Affix.class), null, false);

        BlockPos northDoor = origin.offset(7, 1, 0);
        BlockPos returnFromNorth = Pocket2.returnPos(parent, northDoor);
        check(returnFromNorth.equals(origin.offset(8, 1, 2)), "return spot for a north-wall door");
        check(Pocket2.returnYaw(parent, returnFromNorth) == InstanceLayout.yawFor(DoorMask.Direction.SOUTH),
                "facing south, back into the room, from a north-wall door");

        BlockPos southDoor = origin.offset(7, 1, RoomGeometry.CELL - 1);
        BlockPos returnFromSouth = Pocket2.returnPos(parent, southDoor);
        check(returnFromSouth.equals(origin.offset(8, 1, RoomGeometry.CELL - 3)), "return spot for a south-wall door");
        check(Pocket2.returnYaw(parent, returnFromSouth) == InstanceLayout.yawFor(DoorMask.Direction.NORTH),
                "facing north, back into the room, from a south-wall door");

        BlockPos westDoor = origin.offset(0, 1, 7);
        BlockPos returnFromWest = Pocket2.returnPos(parent, westDoor);
        check(returnFromWest.equals(origin.offset(2, 1, 8)), "return spot for a west-wall door");
        check(Pocket2.returnYaw(parent, returnFromWest) == InstanceLayout.yawFor(DoorMask.Direction.EAST),
                "facing east, back into the room, from a west-wall door");

        BlockPos eastDoor = origin.offset(RoomGeometry.CELL - 1, 1, 7);
        BlockPos returnFromEast = Pocket2.returnPos(parent, eastDoor);
        check(returnFromEast.equals(origin.offset(RoomGeometry.CELL - 3, 1, 8)), "return spot for an east-wall door");
        check(Pocket2.returnYaw(parent, returnFromEast) == InstanceLayout.yawFor(DoorMask.Direction.WEST),
                "facing west, back into the room, from an east-wall door");
    }

    /** {@link Pocket2#isDoorBlock} is true only for the two-wide, two-tall door pair, not the rest of the wall. */
    private static void testIsDoorBlockMatchesTheDoorPairOnly() {
        BlockPos origin = new BlockPos(7000, 64, 8000);
        PlanGeometry geometry = singleCellGeometry(origin);
        BlockPos door = origin.offset(RoomGeometry.DOOR_MIN, 1, 0);

        check(Pocket2.isDoorBlock(geometry, door, origin.offset(RoomGeometry.DOOR_MIN, 1, 0)),
                "the door's own lower-left block");
        check(Pocket2.isDoorBlock(geometry, door, origin.offset(RoomGeometry.DOOR_MIN, 2, 0)),
                "the upper half directly above it");
        check(Pocket2.isDoorBlock(geometry, door, origin.offset(RoomGeometry.DOOR_MAX, 1, 0)),
                "the other half of the two-wide pair");
        check(!Pocket2.isDoorBlock(geometry, door, origin.offset(RoomGeometry.DOOR_MIN - 1, 1, 0)),
                "one block outside the pair, same wall, is not the door");
        check(!Pocket2.isDoorBlock(geometry, door, origin.offset(RoomGeometry.DOOR_MIN, 3, 0)),
                "a third block up (the lintel) is not the door");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void check(long actual, long expected) {
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }
}
