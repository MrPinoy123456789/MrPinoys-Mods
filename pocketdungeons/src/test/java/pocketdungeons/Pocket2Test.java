package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.UUID;

public class Pocket2Test {

    public static void main(String[] args) {
        testChildRecordRoundTrip();
        testChildNotKeystoneRun();
        testAllocateSlotNear();
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
