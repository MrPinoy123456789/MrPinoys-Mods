package charter.mc.granary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractBannerBlock;

/** Finds a banner beside a spot: the camp store stands next to its war banner (PLAN.md §6.1). */
final class Banners {

    /** How far from the store a banner may stand and still count as beside it. */
    static final int REACH = 3;

    private Banners() {
    }

    static boolean near(ServerLevel level, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-REACH, -REACH, -REACH), at.offset(REACH, REACH, REACH))) {
            if (level.getBlockState(p).getBlock() instanceof AbstractBannerBlock) return true;
        }
        return false;
    }
}
