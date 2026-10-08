package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * PD-172: the herd's gold blocks stand in the editable interior, not sunk into the shell floor
 * row where nobody can mine them. A cell stamped from the old baked template, which has the gold
 * at y=0, is repaired as it is stamped.
 */
public final class HerdGameTest {

    private static final int[][] SPOTS = {{6, 6}, {10, 10}, {6, 10}, {10, 6}};

    @GameTest
    public void theHerdsGoldIsAboveTheShellFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 0, 0));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(o.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            }
        }
        for (int[] spot : SPOTS) {
            level.setBlock(o.offset(spot[0], 0, spot[1]), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        }

        KnowledgeSpecs.placeHerdGold(level, o);
        KnowledgeSpecs.placeHerdGold(level, o);

        BlockPos cellOrigin = o;
        for (int[] spot : SPOTS) {
            BlockPos gold = o.offset(spot[0], 1, spot[1]);
            helper.assertTrue(level.getBlockState(gold).is(Blocks.GOLD_BLOCK), "gold stands at " + gold);
            helper.assertTrue(!RoomProtection.isShell(gold, cellOrigin), "and it is not in the shell");
            helper.assertTrue(level.getBlockState(o.offset(spot[0], 0, spot[1])).is(Blocks.STONE_BRICKS),
                    "the sunk block is back to the floor");
        }
        helper.succeed();
    }
}
