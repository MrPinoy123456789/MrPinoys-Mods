package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The dungeon dimension's own world rules: a trial spawner may spawn a mob its config names on any floor
 * (PD-194), and fire never spreads (PD-195). The game tests have no dungeon dimension, so they switch
 * {@link DungeonWorldRules#everywhere} on for the level they have.
 */
public final class DungeonWorldRulesGameTest {

    /** Away from the test structures. */
    private static final BlockPos FAR = new BlockPos(0, 150, 0);

    @GameTest(maxTicks = 40)
    public void aTrialSpawnerWolfNeedsNoGrass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setBlock(FAR.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(FAR, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(FAR.above(), Blocks.AIR.defaultBlockState(), 3);
        boolean spawner;
        boolean natural;
        DungeonWorldRules.everywhere = true;
        try {
            spawner = SpawnPlacements.checkSpawnRules(EntityTypes.WOLF, level, EntitySpawnReason.TRIAL_SPAWNER,
                    FAR, level.getRandom());
            natural = SpawnPlacements.checkSpawnRules(EntityTypes.WOLF, level, EntitySpawnReason.NATURAL,
                    FAR, level.getRandom());
        } finally {
            DungeonWorldRules.everywhere = false;
            level.setBlock(FAR.below(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.assertTrue(spawner, "a trial spawner's wolf may stand on bare stone");
        helper.assertTrue(!natural, "a naturally spawning wolf still needs grass");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void fireDoesNotSpreadInTheDungeon(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = FAR.offset(10, 0, 0);
        BlockPos fire = base.above();
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockPos[] ring = {base, base.east(), base.west(), base.north(), base.south()};
        for (BlockPos pos : ring) {
            level.setBlock(pos, planks, 3);
        }
        level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 3);
        boolean intact = true;
        DungeonWorldRules.everywhere = true;
        try {
            for (int i = 0; i < 400; i++) {
                BlockState state = level.getBlockState(fire);
                if (state.is(Blocks.FIRE)) {
                    state.tick(level, fire, level.getRandom());
                }
            }
            for (BlockPos pos : ring) {
                intact &= level.getBlockState(pos).is(Blocks.SPRUCE_PLANKS);
            }
        } finally {
            DungeonWorldRules.everywhere = false;
            for (BlockPos pos : ring) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(fire, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.assertTrue(intact, "fire beside spruce planks burned none of them in 400 ticks");
        helper.succeed();
    }
}
