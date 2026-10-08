package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * PD-164: the toll rooms (Barred Vault, Ominous Bargain). A cell stamped from
 * the old baked template (a 2 wide door in a four block partition, a hopper
 * holding four stacks of the toll item, a comparator and dust that do nothing)
 * is repaired as it is stamped: nothing is left in the hopper to take, the dead
 * redstone is gone, the partition runs wall to wall, and dropping the toll item
 * in the hopper opens the door and takes one.
 */
public final class TollRoomGameTest {

    private static final int DOOR_Z = 8;

    /** The old template, rotation 0: what {@code SpurSpecs} used to bake. */
    private static void stampOldTemplate(ServerLevel level, BlockPos o, net.minecraft.world.item.Item toll) {
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.OPEN, false);
        for (int x = 7; x <= 8; x++) {
            DoorHingeSide hinge = x == 7 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            level.setBlock(o.offset(x, 1, DOOR_Z), lower.setValue(DoorBlock.HINGE, hinge), 3);
            level.setBlock(o.offset(x, 2, DOOR_Z), lower.setValue(DoorBlock.HINGE, hinge)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
            level.setBlock(o.offset(x, 3, DOOR_Z), RoomBuilder.WALL, 3);
        }
        for (int y = 1; y <= 3; y++) {
            level.setBlock(o.offset(6, y, DOOR_Z), RoomBuilder.WALL, 3);
            level.setBlock(o.offset(9, y, DOOR_Z), RoomBuilder.WALL, 3);
        }
        level.setBlock(o.offset(8, 1, 7), Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.SOUTH), 3);
        if (level.getBlockEntity(o.offset(8, 1, 7)) instanceof HopperBlockEntity hopper) {
            for (int i = 0; i < 4; i++) {
                hopper.setItem(i, new ItemStack(toll, 64));
            }
        }
        level.setBlock(o.offset(9, 1, 10), Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.FACING, Direction.NORTH), 3);
        level.setBlock(o.offset(9, 1, 9), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
    }

    private static void forceCell(ServerLevel level, BlockPos o, boolean on) {
        for (int dx = 0; dx <= 16; dx += 16) {
            for (int dz = 0; dz <= 16; dz += 16) {
                level.setChunkForced((o.getX() + dx) >> 4, (o.getZ() + dz) >> 4, on);
            }
        }
    }

    @GameTest(maxTicks = 80)
    public void aTollRoomIsRepairedWhenItIsStamped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = new BlockPos(10496, 120, 10496);
        forceCell(level, o, true);
        helper.runAfterDelay(10, () -> {
            try {
                stampOldTemplate(level, o, Items.TRIAL_KEY);
                helper.assertTrue(SpurToll.tollFor("barred_vault") == Items.TRIAL_KEY
                                && SpurToll.tollFor("ominous_bargain") == Items.GOLD_INGOT
                                && SpurToll.tollFor("frame_lock") == null,
                        "only the two toll rooms have a toll");

                SpurToll.apply(level, o, Items.TRIAL_KEY);

                HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(o.offset(8, 1, 7));
                helper.assertTrue(hopper != null && hopper.isEmpty(),
                        "the hopper holds nothing to take (it held 256 trial keys)");
                helper.assertTrue(level.getBlockState(o.offset(9, 1, 10)).isAir()
                                && level.getBlockState(o.offset(9, 1, 9)).isAir(),
                        "the dead comparator and dust are gone");
                for (int x = 1; x <= 14; x++) {
                    for (int y = 1; y <= 5; y++) {
                        boolean doorway = (x == 7 || x == 8) && y <= 2;
                        helper.assertTrue(doorway ? level.getBlockState(o.offset(x, y, DOOR_Z)).is(Blocks.IRON_DOOR)
                                        : !level.getBlockState(o.offset(x, y, DOOR_Z)).isAir(),
                                "the partition runs wall to wall with the iron door the only way through: "
                                        + x + "," + y);
                    }
                }
                helper.assertTrue(Locks.isArmed(o) == false && Locks.hint(o) != null,
                        "the lock is armed (and does not count as an unsolved cell for the dwell clock)");

                // Drop a key in: after a lock tick the door is latched open and the hopper keeps one fewer.
                HopperBlockEntity toll = (HopperBlockEntity) level.getBlockEntity(o.offset(8, 1, 7));
                toll.setItem(0, new ItemStack(Items.TRIAL_KEY, 3));
                helper.runAfterDelay(30, () -> {
                    try {
                        BlockState door = level.getBlockState(o.offset(8, 1, DOOR_Z));
                        helper.assertTrue(door.is(Blocks.IRON_DOOR) && door.getValue(DoorBlock.OPEN),
                                "dropping a key in the hopper latches the door open");
                        HopperBlockEntity after = (HopperBlockEntity) level.getBlockEntity(o.offset(8, 1, 7));
                        helper.assertValueEqual(after.getItem(0).getCount(), 2, "the toll took exactly one key");
                        helper.assertTrue(Locks.hint(o) == null, "the lock is spent");
                        helper.succeed();
                    } finally {
                        Locks.clear(o);
                        forceCell(level, o, false);
                    }
                });
            } catch (RuntimeException e) {
                Locks.clear(o);
                forceCell(level, o, false);
                throw e;
            }
        });
    }
}
