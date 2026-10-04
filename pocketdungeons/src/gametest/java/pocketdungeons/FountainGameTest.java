package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;

/** The dead-end fountain (playtest 2026-10-02-1): seeded roll, the boon applies, a second use does nothing. */
public final class FountainGameTest {

    @GameTest
    public void theRollIsSeededAndTheChanceIsHonoured(GameTestHelper helper) {
        BlockPos cell = new BlockPos(4096, 120, 4096);
        helper.assertTrue(Fountain.roll(42L, cell, 0.0) == null, "chance 0 never rolls a fountain");
        Fountain.Boon first = Fountain.roll(42L, cell, 1.0);
        helper.assertTrue(first != null, "chance 1 always rolls a fountain");
        helper.assertTrue(first == Fountain.roll(42L, cell, 1.0), "the same seed and cell roll the same boon");
        helper.succeed();
    }

    @GameTest
    public void aFountainGivesItsBoonOnceThenRunsDry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(base, Fountain.Boon.HEAL.pedestal.defaultBlockState(), 3);
        level.setBlock(base.above(), Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3), 3);
        helper.assertTrue(Fountain.boonAt(level, base.above()) == Fountain.Boon.HEAL, "the pedestal names the boon");
        player.setHealth(3.0f);
        helper.assertTrue(!Fountain.onUse(player, level, base.above(), false), "not a fountain outside the dungeon");
        helper.assertTrue(Fountain.onUse(player, level, base.above(), true), "the first drink is claimed");
        helper.assertTrue(player.getHealth() >= player.getMaxHealth() - 0.01f, "full heal");
        helper.assertTrue(level.getBlockState(base.above()).is(Blocks.CAULDRON), "the cauldron is empty");
        player.setHealth(3.0f);
        helper.assertTrue(!Fountain.onUse(player, level, base.above(), true), "the second use is not a fountain");
        helper.assertTrue(player.getHealth() < 4.0f, "the second use gave nothing");
        // A water bucket refills the cauldron; it must not become a fountain again.
        level.setBlock(base.above(), Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3), 3);
        helper.assertTrue(Fountain.boonAt(level, base.above()) == null, "a refilled cauldron is no fountain");
        helper.assertTrue(!Fountain.onUse(player, level, base.above(), true), "a refill gives nothing");
        helper.assertTrue(player.getHealth() < 4.0f, "the refill gave nothing");

        BlockPos food = helper.absolutePos(new BlockPos(4, 2, 2));
        level.setBlock(food, Fountain.Boon.FOOD.pedestal.defaultBlockState(), 3);
        level.setBlock(food.above(), Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3), 3);
        player.getFoodData().setFoodLevel(2);
        Fountain.onUse(player, level, food.above(), true);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 20, "food fills the bar");
        helper.succeed();
    }
}
