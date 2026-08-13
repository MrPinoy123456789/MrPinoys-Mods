package wondrous;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Loaded block entities implementing {@link Container} within a box around a centre.
 *
 * <p>Radius 8 is a 17³ box — 4913 positions. That is fine for a manual right-click
 * and catastrophic on a tick loop. No caller in this mod may call this from
 * {@code Aura} or any scheduled pass.
 */
public final class Nearby {

    private Nearby() {}

    /** Loaded block entities implementing Container within {@code radius} of {@code centre}. */
    public static List<Container> containers(ServerLevel level, BlockPos centre, int radius) {
        List<Container> out = new ArrayList<>();
        BlockPos min = centre.offset(-radius, -radius, -radius);
        BlockPos max = centre.offset(radius, radius, radius);

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) {
                out.add(container);
            }
        }

        return out;
    }
}
