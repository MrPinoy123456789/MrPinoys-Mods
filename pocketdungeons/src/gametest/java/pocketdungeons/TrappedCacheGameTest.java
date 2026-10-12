package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Loaded's trapped cache (affix workshop 2026-10-11, row 4): the first opening of the tool chest springs an
 * ambush around it, once, off the door lanes, and the chest still gives its loot.
 */
public final class TrappedCacheGameTest {

    @GameTest(maxTicks = 120)
    @SuppressWarnings("removal")
    public void openingTheCacheSpringsOneAmbushAndKeepsTheLoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(BlockPos.ZERO);
        BlockPos origin = new BlockPos(base.getX() & ~15, base.getY(), base.getZ() & ~15);
        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;
        level.setChunkForced(chunkX, chunkZ, true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(level, origin.getX() + 12.5, origin.getY() + 1, origin.getZ() + 12.5, Set.of(), 0F, 0F, false);
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 1; y <= 4; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            BlockPos chest = origin.offset(2, 1, 2);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            RandomizableContainer container = (RandomizableContainer) level.getBlockEntity(chest);
            container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                    Identifier.parse(TrappedCache.DEFAULT_TABLE)));
            helper.assertTrue(TrappedCache.isSealedCache(level, chest), "the cache is recognised before it is opened");

            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
            InteractionResult first = TrappedCache.onUse(player, level, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(first == InteractionResult.PASS, "the ambush never cancels the open");
            // Vanilla's open follows the PASS and unpacks the table; do the same here.
            container.unpackLootTable(player);
            helper.assertTrue(!((Container) container).isEmpty(), "the chest still gives its loot");
            helper.assertTrue(!TrappedCache.isSealedCache(level, chest), "an opened cache is no longer sealed");
            // A second opening finds no table and springs nothing.
            TrappedCache.onUse(player, level, InteractionHand.MAIN_HAND, hit);

            AABB cell = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                    origin.getX() + 16, origin.getY() + 6, origin.getZ() + 16);
            helper.assertTrue(level.getEntitiesOfClass(Mob.class, cell, e -> e.entityTags().contains(TrappedCache.TAG)).isEmpty(),
                    "nobody stands before the telegraph has run");

            helper.runAfterDelay(TrappedCache.DELAY_TICKS + 10, () -> {
                try {
                    List<Mob> mobs = level.getEntitiesOfClass(Mob.class, cell,
                            e -> e.entityTags().contains(TrappedCache.TAG));
                    helper.assertTrue(mobs.size() == TrappedCache.ambushSize(1),
                            "the ambush is " + TrappedCache.ambushSize(1) + " mobs, once; found " + mobs.size());
                    for (Mob mob : mobs) {
                        int lx = mob.blockPosition().getX() - origin.getX();
                        int lz = mob.blockPosition().getZ() - origin.getZ();
                        helper.assertTrue(!TrappedCache.inLane(lx, lz), "no ambush mob stands on a door lane: " + lx + "," + lz);
                        mob.discard();
                    }
                    helper.succeed();
                } finally {
                    release(level, chunkX, chunkZ);
                    player.discard();
                }
            });
        } catch (RuntimeException | Error e) {
            release(level, chunkX, chunkZ);
            player.discard();
            throw e;
        }
    }

    private static void release(ServerLevel level, int chunkX, int chunkZ) {
        level.setChunkForced(chunkX, chunkZ, false);
    }
}
