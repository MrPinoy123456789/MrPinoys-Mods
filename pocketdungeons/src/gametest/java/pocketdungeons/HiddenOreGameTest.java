package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Design 2026-10-06-1 item 7: the Mineshaft buries ore in the walls of {@code mineshaft_seam}.
 * Every block it places is enclosed by solid blocks, is not in the shell, is of the dungeon's
 * hidden ore blocks, and is registered as a node.
 */
public final class HiddenOreGameTest {

    @GameTest(maxTicks = 100)
    public void mineshaftSeamHidesEnclosedRegisteredOre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NodeStamper.Context ctx = NodeStamper.contextFor("pocketdungeons:mineshaft", "adit", false);
        helper.assertTrue(ctx.hiddenOre() != null && !ctx.hiddenBlocks().isEmpty(),
                "the Mineshaft carries a hiddenOre field");
        RoomManifest.Entry seam = RoomManifest.current().byName("mineshaft_seam");
        helper.assertTrue(seam != null, "mineshaft_seam is in the manifest");

        BlockPos origin = new BlockPos(7936, 120, 7936);
        for (int dx = 0; dx < 2 * 16; dx += 16) {
            for (int dz = 0; dz < 2 * 16; dz += 16) {
                level.setChunkForced((origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4, true);
            }
        }
        try {
            TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                    Identifier.parse(seam.meta.template), 0, 1L,
                    Identifier.parse("pocketdungeons:theme_rootworks"));
            int placed = 0;
            for (long seed = 1; seed <= 60; seed++) {
                Set<BlockPos> nodes = new HashSet<>();
                NodeStamper.placeHiddenOre(level, origin, seam.meta.spanY, ctx, seed, nodes);
                for (BlockPos pos : nodes) {
                    placed++;
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(pos).getBlock()).toString();
                    helper.assertTrue(ctx.hiddenBlocks().contains(id), "a hidden block is one of the dungeon's: " + id);
                    helper.assertTrue(!RoomProtection.isShell(pos, origin), "never in the shell: " + pos);
                    for (Direction side : Direction.values()) {
                        BlockState next = level.getBlockState(pos.relative(side));
                        helper.assertTrue(!next.isAir() && next.getFluidState().isEmpty(),
                                "a hidden block never touches air or water: " + pos + " " + side);
                    }
                }
            }
            helper.assertTrue(placed > 0, "some seed buried ore in mineshaft_seam");
        } finally {
            for (int dx = 0; dx < 2 * 16; dx += 16) {
                for (int dz = 0; dz < 2 * 16; dz += 16) {
                    level.setChunkForced((origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4, false);
                }
            }
        }
        helper.succeed();
    }
}
