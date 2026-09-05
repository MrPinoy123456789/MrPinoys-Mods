package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * M64 (SITUATIONS_SPEC 6.4 and 12.3): a spent optional tool may lose treasure,
 * never the mandatory exit.
 *
 * <p>Spec 12.3 makes a cleared cell the player's safe ground, and a gate that
 * could close behind them would take that away. {@link Locks} drops a lock the
 * moment it fires, so a door that has been earned stays open even if the item
 * that earned it is later spent, moved or lost. That is the block-level
 * guarantee behind the boolean solvability model's item-return rule (6.4): a
 * capability, once reached, stays reached at every deeper distance, because the
 * gate does not re-arm.
 *
 * <p>This scenario arms an {@link Locks.Kind#ITEM_ANY} lock over a cell, places
 * the tool in the chest to open the door, then empties the chest (the tool is
 * spent) and asserts the door stays open. A door that re-closed when the chest
 * was emptied would strand the player behind their own solved gate, which is
 * exactly the failure 12.3 forbids.
 *
 * <p>Same package rationale as {@link CustodyGameTest}: {@link Locks} is
 * package-private, and sharing the package is cheaper than opening it.
 */
public final class SituationGameTest {

    /** Ticks to wait for {@link Locks#tick} to evaluate. The period is 10 ticks. */
    private static final long LOCK_TICK_WAIT = 25L;

    @GameTest(maxTicks = 100)
    public void spentOptionalToolStillHasExit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // helper.setBlock takes structure-local coordinates; Locks.arm takes
        // world coordinates. Keep both and convert.
        BlockPos doorLowerLocal = new BlockPos(1, 1, 1);
        BlockPos chestLocal = new BlockPos(3, 1, 1);

        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos doorLower = helper.absolutePos(doorLowerLocal);
        BlockPos doorUpper = doorLower.above();
        BlockPos chestPos = helper.absolutePos(chestLocal);

        // Place an iron door (lower and upper halves) and a chest within the
        // cell scan range. The lock scans from the origin outward, so these
        // positions are well inside it.
        helper.setBlock(doorLowerLocal, Blocks.IRON_DOOR.defaultBlockState());
        helper.setBlock(doorLowerLocal.above(), Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(chestLocal, Blocks.CHEST.defaultBlockState());

        // Arm the lock: the door opens when any item is in the chest.
        Locks.arm(level, origin, Locks.Kind.ITEM_ANY, null);
        helper.assertTrue(Locks.isArmed(origin),
                "the lock armed over the cell with a door and a chest");

        // Place the tool in the chest. This is the gate's solution.
        placeItem(level, chestPos, new ItemStack(Items.STICK, 1));

        // Wait for Locks.tick to evaluate and open the door.
        helper.runAfterDelay(LOCK_TICK_WAIT, () -> {
            boolean openAfterSolve = isDoorOpen(level, doorLower);
            helper.assertTrue(openAfterSolve,
                    "the door opened after the tool was placed in the chest");

            // Spend the tool: empty the chest. This is the "spent optional
            // tool" the milestone is about. The player used the item to open
            // the gate, and the item is now gone.
            removeItems(level, chestPos);
            helper.assertTrue(chestIsEmpty(level, chestPos),
                    "the chest was emptied; the tool is spent");

            // Wait again. The door must stay open: the lock fired and dropped,
            // so there is nothing to re-evaluate and nothing to re-close.
            helper.runAfterDelay(LOCK_TICK_WAIT, () -> {
                boolean openAfterSpend = isDoorOpen(level, doorLower);
                helper.assertTrue(openAfterSpend,
                        "the door stayed open after the tool was spent; a gate "
                                + "that re-closes would strand the player behind "
                                + "their own solved gate (spec 12.3)");

                // Clean up so the static ACTIVE map does not leak into sibling
                // tests. The lock already dropped itself when it fired, but
                // clear is idempotent and costs nothing.
                Locks.clear(origin);
                helper.succeed();
            });
        });
    }

    // ---- helpers ----

    private static void placeItem(ServerLevel level, BlockPos pos, ItemStack stack) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            container.setItem(0, stack);
        }
    }

    private static void removeItems(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            container.clearContent();
        }
    }

    private static boolean chestIsEmpty(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return !(be instanceof Container container) || container.isEmpty();
    }

    private static boolean isDoorOpen(ServerLevel level, BlockPos doorLower) {
        return level.getBlockState(doorLower).is(Blocks.IRON_DOOR)
                && level.getBlockState(doorLower).getValue(DoorBlock.OPEN);
    }
}
