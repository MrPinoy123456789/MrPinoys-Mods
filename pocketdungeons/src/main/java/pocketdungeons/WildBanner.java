package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;

/**
 * The banner of the kingdom that last cleared a wild dungeon (decision 2026-11-04): a standing banner in the nearest
 * dye colour to the kingdom's tag colour, on top of the ruin's tall pillar (two blocks west and north of the lodestone,
 * three up). A real block, so Bedrock players see it too. Placed at the clear when the ruin's chunk is loaded, and
 * otherwise the next time the chunk loads. Nobody's colour (a clear by players in no kingdom) leaves the pillar bare.
 * The collapse's rubble clears it with the rest of the ruin.
 */
final class WildBanner {

    /** Where the banner stands, from the lodestone block. */
    static final int DX = -2;
    static final int DY = 3;
    static final int DZ = -2;

    /** Vanilla's dye colours (the banner texture tints) in {@code DyeColor} id order, white 0 to black 15. */
    static final int[] DYE_RGB = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

    private WildBanner() {}

    /** The dye id nearest to {@code rgb} (plain distance in RGB). Pure. */
    static int nearestDye(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int id = 0; id < DYE_RGB.length; id++) {
            int dr = r - ((DYE_RGB[id] >> 16) & 0xFF);
            int dg = g - ((DYE_RGB[id] >> 8) & 0xFF);
            int db = b - (DYE_RGB[id] & 0xFF);
            long distance = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = id;
            }
        }
        return best;
    }

    /** Puts up (or changes, or takes down) the banner of a lodestone whose chunk is loaded. */
    static void ensure(ServerLevel level, WildState.Lodestone lodestone) {
        BlockPos at = new BlockPos(lodestone.x + DX, lodestone.y + DY, lodestone.z + DZ);
        if (!level.hasChunkAt(at)) {
            return;
        }
        boolean banner = level.getBlockState(at).getBlock() instanceof BannerBlock;
        if (lodestone.lastClearedColour < 0) {
            if (banner) {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            }
            return;
        }
        // Only on the ruin's own pillar, never over something a player built there.
        if (!banner && !level.getBlockState(at).isAir()) {
            return;
        }
        DyeColor dye = DyeColor.byId(nearestDye(lodestone.lastClearedColour));
        var state = BannerBlock.byColor(dye).defaultBlockState();
        if (!level.getBlockState(at).is(state.getBlock())) {
            level.setBlock(at, state, 3);
        }
    }
}
