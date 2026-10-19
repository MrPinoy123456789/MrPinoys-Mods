package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * PD-219: a room whose decor spawns a block-attached entity (the Rotation Lock item frame) keeps it inside the
 * cell, and the template generator's cleanup removes it with the blocks, so none is left behind in the scratch
 * region to log "Block-attached entity at invalid position".
 */
public final class ScratchEntityGameTest {

    private static final int CELL = 16;

    private static List<Entity> attached(ServerLevel level, AABB box) {
        return level.getEntities((Entity) null, box, e -> e instanceof HangingEntity || e instanceof LeashFenceKnotEntity);
    }

    @GameTest(maxTicks = 40)
    public void roomEntitiesStayInTheCellAndCleanupRemovesThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        RoomSpec spec = RoomTemplateGenerator.specNamed("rotation_lock");
        helper.assertTrue(spec != null, "rotation_lock has a spec");
        AABB cell = new AABB(o.getX(), o.getY() - 2, o.getZ(), o.getX() + CELL, o.getY() + CELL, o.getZ() + CELL);
        RoomTemplateGenerator.clear(level, o.below(2), RoomTemplateGenerator.templateSize(1).offset(0, 2, 0));
        try {
            RoomTemplateGenerator.buildForCheck(level, o, spec);
            List<Entity> found = attached(level, cell.inflate(64, 64, 64));
            helper.assertTrue(!found.isEmpty(), "the Rotation Lock builds its item frame");
            for (Entity e : found) {
                helper.assertTrue(cell.contains(e.position()),
                        "block-attached entity " + e.getType().toShortString() + " at " + e.blockPosition().toShortString()
                                + " sits inside the cell at " + o.toShortString());
            }
        } finally {
            RoomTemplateGenerator.clear(level, o.below(2), RoomTemplateGenerator.templateSize(1).offset(0, 2, 0));
        }
        helper.assertTrue(attached(level, cell).isEmpty(), "the generator's cleanup leaves no block-attached entity behind");
        helper.succeed();
    }
}
