package excellentenchants.gametest;

import excellentenchants.SpawnerPlacing;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

/** A spawner from Silk Spawner, placed by a player who is not an operator, keeps its mob (but never a boss). */
public final class SpawnerPlacingGameTest {

    /** The item Silk Spawner drops for a spawner of {@code mob}, built the same way BlockDrops does. */
    private static ItemStack silked(GameTestHelper helper, BlockPos rel, EntityType<?> mob) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(rel, Blocks.SPAWNER);
        SpawnerBlockEntity source = (SpawnerBlockEntity) level.getBlockEntity(helper.absolutePos(rel));
        source.setEntityId(mob, level.getRandom());
        ItemStack stack = new ItemStack(Items.SPAWNER);
        stack.set(DataComponents.BLOCK_ENTITY_DATA,
                TypedEntityData.of(source.getType(), source.saveWithoutMetadata(level.registryAccess())));
        return stack;
    }

    private static SpawnerBlockEntity emptySpawner(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.SPAWNER);
        return (SpawnerBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    @GameTest
    public void aPlayerPlacesASilkedZombieSpawnerAndItKeepsTheZombie(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(!player.canUseGameMasterBlocks(), "the mock player should not be an operator");
        ItemStack stack = silked(helper, new BlockPos(1, 2, 1), EntityTypes.ZOMBIE);
        SpawnerBlockEntity placed = emptySpawner(helper, new BlockPos(3, 2, 1));
        helper.assertTrue(SpawnerPlacing.mobType(placed.saveWithoutMetadata(level.registryAccess())).isEmpty(),
                "a fresh spawner should start empty");
        // The vanilla call placing goes through: without the fix it refuses a non-operator and leaves it empty.
        boolean copied = BlockItem.updateCustomBlockEntityTag(level, player, helper.absolutePos(new BlockPos(3, 2, 1)), stack);
        var mob = SpawnerPlacing.mobType(placed.saveWithoutMetadata(level.registryAccess()));
        helper.assertTrue(copied && mob.isPresent() && mob.get() == EntityTypes.ZOMBIE, "placed spawner mob: " + mob);
        helper.succeed();
    }

    @GameTest
    public void bossesAndNonMobsStayOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var player = helper.makeMockServerPlayerInLevel();
        ItemStack wither = silked(helper, new BlockPos(1, 2, 1), EntityTypes.WITHER);
        SpawnerBlockEntity placed = emptySpawner(helper, new BlockPos(3, 2, 1));
        helper.assertTrue(!SpawnerPlacing.restore(level, player, helper.absolutePos(new BlockPos(3, 2, 1)), wither),
                "a Wither spawner must not be restored");
        helper.assertTrue(SpawnerPlacing.mobType(placed.saveWithoutMetadata(level.registryAccess())).isEmpty(), "still empty");
        helper.assertTrue(SpawnerPlacing.allowed(EntityTypes.SKELETON) && SpawnerPlacing.allowed(EntityTypes.BLAZE),
                "dungeon mobs are allowed");
        helper.assertTrue(!SpawnerPlacing.allowed(EntityTypes.ENDER_DRAGON) && !SpawnerPlacing.allowed(EntityTypes.TNT)
                && !SpawnerPlacing.allowed(EntityTypes.ITEM), "bosses and MISC things are not");
        helper.succeed();
    }
}
