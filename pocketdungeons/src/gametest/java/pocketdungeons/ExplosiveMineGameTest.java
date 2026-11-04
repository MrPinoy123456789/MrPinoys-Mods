package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;

/**
 * The Explosive affix's floor mines flicker like real TNT while their fuse burns (kingdom server, 2026-11-04: they
 * went off with no warning a player could see). Stepping on a mine swaps the TNT block for a barrier, so the floor
 * still holds, with a primed TNT in its place; when the mine goes off the primed TNT is gone, the TNT block is back
 * and nothing around it is broken.
 */
@SuppressWarnings("removal")
public final class ExplosiveMineGameTest {

    @GameTest(maxTicks = 120)
    public void aSteppedMineFlickersThenRestoresItsBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos mine = helper.absolutePos(new BlockPos(2, 1, 2));
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 1, z)), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        RoomContent.placeMineForTesting(level, mine);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.teleportTo(level, mine.getX() + 0.5, mine.getY() + 1, mine.getZ() + 0.5, Set.of(), 0.0F, 0.0F, false);

        RoomContent.tickMinesForTesting(level);
        helper.assertTrue(level.getBlockState(mine).is(Blocks.BARRIER), "the fuse holds the floor with a barrier");
        helper.assertTrue(fuseVisuals(level, mine).size() == 1, "one primed TNT flickers where the mine was");
        // The player walks off: the fuse still runs to the end.
        player.teleportTo(level, mine.getX() + 6.5, mine.getY() + 1, mine.getZ() + 0.5, Set.of(), 0.0F, 0.0F, false);

        for (int i = 0; i < RoomContent.MINE_FUSE_TICKS; i++) {
            RoomContent.tickMinesForTesting(level);
        }
        try {
            helper.assertTrue(level.getBlockState(mine).is(Blocks.TNT), "the TNT block is back once the mine goes off");
            helper.assertTrue(fuseVisuals(level, mine).isEmpty(), "the primed TNT is removed, it never goes off itself");
            helper.assertTrue(level.getBlockState(mine.east()).is(Blocks.STONE), "the blast breaks no blocks");
        } finally {
            RoomContent.forgetMineForTesting(mine);
        }
        helper.succeed();
    }

    private static List<PrimedTnt> fuseVisuals(ServerLevel level, BlockPos mine) {
        return level.getEntitiesOfClass(PrimedTnt.class, new AABB(mine).inflate(2.0),
                tnt -> tnt.getTags().contains(RoomContent.MINE_VISUAL_TAG) && !tnt.isRemoved());
    }
}
