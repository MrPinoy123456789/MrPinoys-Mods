package pocketdungeons.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * The wiring proof for the pocketdungeons gametest harness (M46B).
 *
 * <p>This class exists to show that the source set, the entrypoint and the
 * {@code runGameTest} task actually reach a running dedicated server and place
 * blocks in a test structure. It deliberately tests nothing about this mod.
 * Real scenarios belong in their own classes next to this one.
 *
 * <p>To add a test: write a public, non-static, void method taking a single
 * {@link GameTestHelper}, annotate it with {@link GameTest}, and list the
 * declaring class under the {@code fabric-gametest} entrypoint in
 * {@code src/gametest/resources/fabric.mod.json}. Fabric derives the test id
 * from the class and method names, so no registration code is needed.
 */
public final class HarnessGameTest {
    /**
     * Places a block into the empty 8x8 default structure and asserts it is
     * there. Passing proves the whole chain: gradle task, dedicated server
     * boot, structure placement, entrypoint discovery, and reflection into
     * this method.
     */
    @GameTest
    public void placesABlock(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.GOLD_BLOCK);
        helper.assertBlockPresent(Blocks.GOLD_BLOCK, pos);
        helper.succeed();
    }
}
