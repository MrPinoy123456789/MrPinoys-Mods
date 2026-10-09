package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;

/**
 * Design pass 2026-10-09 (Q3): a full Heard meter makes the room's shrieker scream the vanilla way
 * ({@link PressureSources#shriekAt}); a block that is not
 * a shrieker is not one.
 */
public final class SculkShriekGameTest {

    @SuppressWarnings("removal")
    @GameTest(maxTicks = 40)
    public void aFullMeterMakesTheShriekerScream(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(at, Blocks.SCULK_SHRIEKER.defaultBlockState(), 3);
        try {
            helper.assertTrue(!level.getBlockState(at).getValue(SculkShriekerBlock.SHRIEKING), "it starts quiet");
            helper.assertTrue(PressureSources.shriekAt(level, at, player), "the shrieker screams when asked");
            helper.assertTrue(level.getBlockState(at).getValue(SculkShriekerBlock.SHRIEKING), "and is shrieking");
        } finally {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.assertTrue(!PressureSources.shriekAt(level, at, player), "air is not a shrieker");
        helper.succeed();
    }
}
