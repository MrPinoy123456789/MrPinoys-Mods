package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

/**
 * PD-160: every iron door opens from the side you arrive on. The flooded
 * hall's containment doors are latches that open on use and close again; a
 * connector iron door keeps its lever on another wall of the near room and
 * gets a stone button in the far room.
 */
public final class IronDoorGameTest {

    private static void force(ServerLevel level, BlockPos origin, int spanChunks, boolean on) {
        for (int dx = 0; dx < spanChunks * 16; dx += 16) {
            for (int dz = 0; dz < spanChunks * 16; dz += 16) {
                level.setChunkForced((origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4, on);
            }
        }
    }

    private static boolean open(ServerLevel level, BlockPos lower) {
        BlockState state = level.getBlockState(lower);
        return state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.OPEN)
                && level.getBlockState(lower.above()).getValue(DoorBlock.OPEN);
    }

    @GameTest(maxTicks = 200)
    public void floodedHallDoorsAreLatchesThatOpenAndClose(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        IronDoorLatch.clear();
        BlockPos origin = new BlockPos(7168, 120, 7168);
        force(level, origin, 2, true);
        java.util.List<BlockPos> spawns = TemplateStamper.place(level, level.getServer().getStructureManager(),
                origin, Identifier.parse("pocketdungeons:rooms/flooded_hall"), 0, 1L,
                Identifier.parse("pocketdungeons:theme_deepslate"));
        Situations.apply(level, origin, RoleIds.CORRIDOR, 1, DifficultyProfile.of(5, 1), spawns, 1L,
                Set.of(), "", null, false, "flooded_hall");

        List<BlockPos> latches = TraversalSpecs.latchDoorsAt(level, origin);
        helper.assertTrue(latches.size() >= 4 && latches.size() % 2 == 0,
                "every open doorway holds a door pair (found " + latches.size() + " leaves)");
        for (int dz = RoomGeometry.DOOR_MIN; dz <= RoomGeometry.DOOR_MAX; dz++) {
            BlockPos west = origin.offset(0, 1, dz);
            helper.assertTrue(latches.contains(west), "the west doorway column " + dz + " is a latch");
        }
        helper.assertTrue(level.getBlockState(origin.offset(RoomGeometry.CELL - 2, 2, RoomGeometry.DOOR_MIN - 1))
                .getBlock() != Blocks.STONE_BUTTON, "no button hangs in the water");
        Set<BlockPos> latchSet = Set.copyOf(latches);
        BlockPos first = latches.get(0);
        helper.assertTrue(!open(level, first), "the door starts shut");

        IronDoorLatch.latch(level, first, latchSet);
        for (BlockPos leaf : IronDoorLatch.leavesOfForTest(first, latchSet)) {
            helper.assertTrue(open(level, leaf), "using a latch opens both leaves, both halves: " + leaf);
        }
        helper.runAtTickTime(helper.getTick() + 30, () -> {
            IronDoorLatch.tick(level);
            helper.assertTrue(open(level, first), "the latch is still open half way through");
        });
        helper.runAtTickTime(helper.getTick() + 75, () -> {
            IronDoorLatch.tick(level);
            for (BlockPos leaf : IronDoorLatch.leavesOfForTest(first, latchSet)) {
                helper.assertTrue(!open(level, leaf), "the latch closes after " + IronDoorLatch.LATCH_TICKS
                        + " ticks: " + leaf);
            }
            force(level, origin, 2, false);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void aConnectorDoorHasItsLeverOffTheFrameAndAButtonOnTheFarSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = new BlockPos(7424, 120, 7424);
        BlockPos far = near.offset(RoomGeometry.CELL, 0, 0);
        force(level, near, 3, true);
        try {
            for (BlockPos origin : new BlockPos[]{near, far}) {
                TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                        Identifier.parse("pocketdungeons:rooms/encounter_zombie"), 0, 1L,
                        Identifier.parse("pocketdungeons:theme_deepslate"));
            }
            ConnectorStamper.IronDoor door = ConnectorStamper.applyIronDoor(level, near, DoorMask.Direction.EAST);
            BlockPos lever = door.lever();
            helper.assertTrue(level.getBlockState(lever).is(Blocks.LEVER), "a lever was placed");
            helper.assertTrue(lever.getY() - near.getY() == 2, "the lever stands at y 2, off the frame (y was "
                    + (lever.getY() - near.getY()) + ")");
            helper.assertTrue(lever.getX() - near.getX() != RoomGeometry.CELL - 2,
                    "the lever is not on the door wall");
            helper.assertTrue(door.lowers().size() == 2, "both leaves are reported");

            BlockPos button = ConnectorStamper.applyFarSideButton(level, far, DoorMask.Direction.WEST);
            helper.assertTrue(button != null && level.getBlockState(button).is(Blocks.STONE_BUTTON),
                    "a stone button stands in the far room");
            helper.assertTrue(button.getX() - far.getX() == 1, "the button is one block inside the far wall");
        } finally {
            force(level, near, 3, false);
        }
        helper.succeed();
    }
}
