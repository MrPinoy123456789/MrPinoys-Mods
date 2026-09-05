package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * M63 teardown and slot reuse: a slot that stops being used must become
 * available again, on every path out of a teardown including the ones that go
 * wrong.
 *
 * <p>Same package rationale as {@link CustodyGameTest}.
 *
 * <h2>Why the dungeon dimension being absent is the scenario, not a limitation</h2>
 *
 * <p>A {@code GameTestServer} never creates {@code pocketdungeons:void}
 * (DISCOVERIES trap 18), so {@code server.getLevel(DUNGEON_LEVEL)} is null
 * here. That is normally a reason to push a check into
 * {@code dungeonIntegrationTest} instead. Not this one: "the dungeon level is
 * gone while clears are queued" is a real production state, reachable during
 * shutdown and on a server whose datapack failed to load, and it is precisely
 * the branch where {@link InstanceTeardown#processClears} abandons its queue.
 * The harness hands that state over for free, so the scenario is run where it
 * is cheapest to run.
 *
 * <p>The block-writing half of a teardown (the tick budget, the bedrock
 * envelope, the lower-story reach) needs real geometry and stays a live and
 * integration concern. What is asserted here is the bookkeeping either way:
 * a slot is never left claimed by an instance that no longer exists.
 */
public final class TeardownGameTest {

    /**
     * A queued clear that can never run must still give its slot back.
     *
     * <p>{@code processClears} drops the whole queue when the dungeon level is
     * missing. Dropping the work is defensible; dropping it without releasing
     * the slots is not, because the slot stays in
     * {@link InstanceRegistry#usedSlots} with nothing left to free it, and
     * {@link InstanceRegistry#allocateSlot} then walks past it forever. Every
     * abandoned clear leaks one slot's worth of world for the life of the
     * server.
     */
    @GameTest
    public void aClearAbandonedForAMissingLevelStillFreesItsSlot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        requireNoDungeonLevel(helper, server);

        int slot = InstanceRegistry.allocateSlot();
        queueClearFor(slot, "abandoned clear test");
        helper.assertTrue(InstanceRegistry.usedSlots.contains(slot),
                "the slot is claimed while the clear is queued");

        InstanceTeardown.processClears(server);

        helper.assertValueEqual(InstanceTeardown.pendingClearCountForTesting(), 0,
                "the queue was drained rather than left to retry forever");
        helper.assertFalse(InstanceRegistry.usedSlots.contains(slot),
                "the slot went back to the free list when its clear was abandoned");

        InstanceRegistry.usedSlots.remove(slot);
        helper.succeed();
    }

    /** {@link InstanceTeardown#drainClears} has the same queue and owes the same guarantee. */
    @GameTest
    public void drainingClearsFreesEverySlotItDrops(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        requireNoDungeonLevel(helper, server);

        // A parent and a child, the shape M25 tears down together: a child
        // never outlives its parent, so neither may outlive the other's slot.
        int parent = InstanceRegistry.allocateSlot();
        int child = InstanceRegistry.allocateSlotNear(parent);
        queueClearFor(parent, "parent teardown test");
        queueClearFor(child, "child teardown test");

        InstanceTeardown.drainClears(server);

        helper.assertValueEqual(InstanceTeardown.pendingClearCountForTesting(), 0,
                "draining emptied the queue");
        helper.assertFalse(InstanceRegistry.usedSlots.contains(parent),
                "the parent slot was released");
        helper.assertFalse(InstanceRegistry.usedSlots.contains(child),
                "the child slot was released with it");

        InstanceRegistry.usedSlots.remove(parent);
        InstanceRegistry.usedSlots.remove(child);
        helper.succeed();
    }

    /**
     * A released slot is handed straight back out, and a still-claimed one is
     * not.
     *
     * <p>The reuse rule is the whole point of releasing a slot: an allocator
     * that skips freed slots would leak just as surely as a teardown that never
     * frees them, only more slowly.
     */
    @GameTest
    public void aFreedSlotIsAllocatedAgainAndAClaimedOneIsNot(GameTestHelper helper) {
        int first = InstanceRegistry.allocateSlot();
        int second = InstanceRegistry.allocateSlot();
        helper.assertFalse(first == second, "two live allocations never collide");

        InstanceRegistry.usedSlots.remove(first);
        int reused = InstanceRegistry.allocateSlot();
        helper.assertValueEqual(reused, first, "the freed slot was the next one handed out");
        helper.assertTrue(InstanceRegistry.usedSlots.contains(second),
                "the slot that was never freed is still claimed");

        InstanceRegistry.usedSlots.remove(first);
        InstanceRegistry.usedSlots.remove(second);
        helper.succeed();
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Confirms the harness really is in the missing-dungeon state these
     * scenarios are about, so a future harness that does create the dimension
     * fails loudly here rather than quietly asserting nothing.
     */
    private static void requireNoDungeonLevel(GameTestHelper helper, MinecraftServer server) {
        if (server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL) != null) {
            helper.fail("this scenario asserts the missing-dungeon-level branch, but the level exists; "
                    + "move it to dungeonIntegrationTest");
        }
    }

    private static void queueClearFor(int slot, String reason) {
        BlockPos origin = InstanceRegistry.originForSlot(slot);
        AABB bounds = InstanceRegistry.maximalBounds(origin);
        List<BlockPos> cells = InstanceRegistry.maximalCellOrigins(origin);
        InstanceTeardown.enqueueClearForTesting(slot, bounds, cells, reason);
    }
}
