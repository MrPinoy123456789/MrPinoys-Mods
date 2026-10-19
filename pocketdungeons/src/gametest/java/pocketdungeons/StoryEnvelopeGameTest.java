package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vertical floors phase 1, step 7: the bedrock envelope around two cells stacked in one column
 * (story 0 over story -1). The upper cell's sub-floor and the lower cell's over-ceiling must be
 * the same single bedrock layer, with nothing but the two interiors above and below it, and the
 * wall ring must run unbroken from under the lower floor to over the upper ceiling.
 */
public final class StoryEnvelopeGameTest {

    private static final int CELL = RoomGeometry.CELL;
    private static final int CEILING_Y = RoomGeometry.CEILING_Y;
    private static final int H = RoomGeometry.STORY_HEIGHT;

    @GameTest(maxTicks = 20)
    public void stackedCellsShareOneBedrockLayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        BlockPos base = new BlockPos(((near.getX() + 6144) >> 4) << 4, near.getY() + 24,
                ((near.getZ() + 6144) >> 4) << 4);
        PlanCell upper = new PlanCell(0, 0, 0);
        PlanCell lower = new PlanCell(0, -1, 0);
        PlanGeometry geometry = PlanGeometry.of(base, List.of(upper, lower));
        BlockPos up = geometry.cellOrigin(upper);
        BlockPos low = geometry.cellOrigin(lower);
        int cx = base.getX() >> 4;
        int cz = base.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(cx + dx, cz + dz, true);
            }
        }
        try {
            clear(level, up);
            BedrockEnvelope.apply(level, geometry, Set.of(), Map.of(), Set.of());

            int shared = up.getY() - 1;
            helper.assertTrue(low.getY() + CEILING_Y + 1 == shared,
                    "the lower over-ceiling and the upper sub-floor are the same layer");
            for (int x = 0; x < CELL; x++) {
                for (int z = 0; z < CELL; z++) {
                    BlockPos column = up.offset(x, 0, z);
                    String at = column.toShortString();
                    helper.assertTrue(isBedrock(level, column.atY(shared)), "shared layer is bedrock at " + at);
                    helper.assertTrue(level.getBlockState(column.atY(shared + 1)).isAir(),
                            "no second layer above the shared one at " + at);
                    helper.assertTrue(level.getBlockState(column.atY(shared - 1)).isAir(),
                            "no second layer below the shared one at " + at);
                    helper.assertTrue(isBedrock(level, column.atY(low.getY() - 1)), "lower sub-floor at " + at);
                    helper.assertTrue(isBedrock(level, column.atY(up.getY() + CEILING_Y + 1)),
                            "upper over-ceiling at " + at);
                }
            }
            for (int y = low.getY() - 1; y <= up.getY() + CEILING_Y + 1; y++) {
                helper.assertTrue(isBedrock(level, new BlockPos(up.getX() - 1, y, up.getZ() + 8)),
                        "west wall ring is unbroken at y " + y);
                helper.assertTrue(isBedrock(level, new BlockPos(up.getX() + CELL, y, up.getZ() + 8)),
                        "east wall ring is unbroken at y " + y);
            }
            // No gap between the interiors: the only solid block in a column between the two
            // outer layers is the shared one.
            int solid = 0;
            for (int y = low.getY(); y <= up.getY() + CEILING_Y; y++) {
                if (!level.getBlockState(up.offset(8, 0, 8).atY(y)).isAir()) {
                    solid++;
                }
            }
            helper.assertValueEqual(solid, 1, "solid blocks between the two interiors' outer layers");
        } finally {
            clear(level, up);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setChunkForced(cx + dx, cz + dz, false);
                }
            }
        }
        helper.succeed();
    }

    private static boolean isBedrock(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.BEDROCK);
    }

    private static void clear(ServerLevel level, BlockPos up) {
        for (int x = -1; x <= CELL; x++) {
            for (int z = -1; z <= CELL; z++) {
                for (int y = up.getY() - H - 1; y <= up.getY() + CEILING_Y + 1; y++) {
                    level.setBlock(new BlockPos(up.getX() + x, y, up.getZ() + z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }
}
