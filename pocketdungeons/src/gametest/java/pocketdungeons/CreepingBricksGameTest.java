package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Roadmap T24: the Infestation's creeping bricks. A brick walks mossy, stone, infested and bursts into exactly one
 * silverfish with nothing dropped; a brick broken first releases nothing; the room and floor caps hold; a ripe
 * brick waits while the silverfish cap is full; a field that ends gives the blocks back; and a gnawed room marks
 * deterministic seeds that start growing when a member walks in. The tests drive {@link CreepingBricks.Field#tick}
 * with a made up clock instead of waiting game time.
 */
public final class CreepingBricksGameTest {

    private static long mossy() {
        return PocketDungeonsConfig.creepingMossySeconds() * 20L;
    }

    private static long stone() {
        return PocketDungeonsConfig.creepingStoneSeconds() * 20L;
    }

    private static long life() {
        return mossy() + stone() + PocketDungeonsConfig.creepingInfestedSeconds() * 20L;
    }

    private static List<Entity> near(ServerLevel level, BlockPos pos, boolean items) {
        AABB box = new AABB(pos).inflate(3);
        return level.getEntities((Entity) null, box,
                e -> items ? e.getType() == EntityTypes.ITEM : e.getType() == EntityTypes.SILVERFISH);
    }

    private static void discardAll(List<Entity> entities) {
        for (Entity entity : entities) {
            entity.discard();
        }
    }

    @GameTest
    public void aBrickWalksItsStagesAndBurstsIntoOneSilverfish(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos floor = helper.absolutePos(new BlockPos(0, 0, 0));
        CreepingBricks.Field field = CreepingBricks.open(level, floor, 4, true);
        try {
            helper.assertTrue(field.plant(pos, 0), "an air spot takes a brick");
            helper.assertTrue(level.getBlockState(pos).is(Blocks.MOSSY_STONE_BRICKS), "it starts mossy");
            field.tick(mossy() - 1);
            helper.assertTrue(level.getBlockState(pos).is(Blocks.MOSSY_STONE_BRICKS), "still mossy a tick early");
            field.tick(mossy());
            helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE_BRICKS), "then stone brick");
            field.tick(mossy() + stone());
            helper.assertTrue(level.getBlockState(pos).is(Blocks.INFESTED_STONE_BRICKS), "then infested");
            helper.assertTrue(near(level, pos, false).isEmpty(), "nothing has hatched yet");
            field.tick(life());
            helper.assertTrue(level.getBlockState(pos).isAir(), "the burst removes the block");
            helper.assertValueEqual(near(level, pos, false).size(), 1, "exactly one silverfish");
            helper.assertTrue(near(level, pos, true).isEmpty(), "no item drops: nothing leaked");
            helper.assertValueEqual(field.collect().size(), 1, "the field hands the silverfish to its owner");
            helper.assertValueEqual(field.active(), 0, "the burst brick is spent");
            discardAll(near(level, pos, false));
        } finally {
            field.close();
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    @GameTest
    public void aBrickBrokenFirstReleasesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos b = helper.absolutePos(new BlockPos(4, 2, 2));
        CreepingBricks.Field field = CreepingBricks.open(level, helper.absolutePos(BlockPos.ZERO), 4, true);
        try {
            helper.assertTrue(field.plant(a, 0) && field.plant(b, 0), "two bricks");
            field.tick(mossy() + stone());
            // a is mined by a member: the handler calls smash.
            helper.assertTrue(CreepingBricks.isBrick(level, a), "the brick is known while it grows");
            helper.assertTrue(field.smash(a), "smashing a brick reports it");
            helper.assertTrue(level.getBlockState(a).isAir(), "the block is gone");
            // b is knocked out some other way (an infested brick, then replaced).
            level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            field.tick(life() + 100);
            helper.assertTrue(near(level, a, false).isEmpty() && near(level, b, false).isEmpty(),
                    "no silverfish from either");
            helper.assertTrue(near(level, a, true).isEmpty() && near(level, b, true).isEmpty(), "no drops");
            helper.assertValueEqual(field.active(), 0, "both timers are cancelled");
            helper.assertTrue(!CreepingBricks.isBrick(level, a), "a smashed brick is no longer tracked");
        } finally {
            field.close();
        }
        helper.succeed();
    }

    @GameTest
    public void theRoomAndFloorCapsHold(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos base = helper.absolutePos(new BlockPos(1, 2, 1));
        List<BlockPos> placed = new ArrayList<>();
        CreepingBricks.Field small = CreepingBricks.open(level, floor, 2, false);
        CreepingBricks.Field wide = CreepingBricks.open(level, floor, 64, false);
        try {
            int ok = 0;
            for (int i = 0; i < 4; i++) {
                BlockPos at = base.offset(i, 0, 0);
                placed.add(at);
                if (small.plant(at, 0)) {
                    ok++;
                }
            }
            helper.assertValueEqual(ok, 2, "a room of cap 2 grows two bricks");
            int floorCap = PocketDungeonsConfig.creepingFloorCap();
            int planted = 0;
            for (int i = 0; i < floorCap + 6; i++) {
                BlockPos at = base.offset(i % 6, 1, i / 6 * 2 + 2);
                placed.add(at);
                if (wide.plant(at, 0)) {
                    planted++;
                }
            }
            helper.assertValueEqual(planted + small.active(), floorCap, "the floor never holds more than its cap");
            helper.assertValueEqual(CreepingBricks.floorActive(floor), floorCap, "floorActive adds the fields");
        } finally {
            small.close();
            wide.close();
            for (BlockPos at : placed) {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aRipeBrickWaitsWhileTheSilverfishCapIsFull(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        CreepingBricks.Field field = CreepingBricks.open(level, helper.absolutePos(BlockPos.ZERO), 4, true);
        try {
            field.plant(pos, 0);
            field.releaseBudget = 0;
            field.tick(life() + 200);
            helper.assertTrue(level.getBlockState(pos).is(Blocks.INFESTED_STONE_BRICKS),
                    "with the cap full the brick stays, infested and shaking");
            helper.assertTrue(near(level, pos, false).isEmpty(), "no silverfish while the cap is full");
            field.releaseBudget = 1;
            field.tick(life() + 220);
            helper.assertValueEqual(near(level, pos, false).size(), 1, "room under the cap: it bursts");
            helper.assertValueEqual(field.releaseBudget, 0, "and spends the budget");
            discardAll(near(level, pos, false));
        } finally {
            field.close();
        }
        helper.succeed();
    }

    @GameTest
    public void aFieldThatEndsGivesTheBlocksBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos b = helper.absolutePos(new BlockPos(4, 2, 2));
        CreepingBricks.Field field = CreepingBricks.open(level, helper.absolutePos(BlockPos.ZERO), 4, false);
        field.plant(a, 0);
        field.plant(b, 0);
        field.tick(mossy() + stone());
        helper.assertTrue(level.getBlockState(a).is(Blocks.INFESTED_STONE_BRICKS), "mid way: infested");
        field.close();
        helper.assertTrue(level.getBlockState(a).isAir() && level.getBlockState(b).isAir(),
                "a floor that ends mid way restores the original (air) block");
        helper.assertValueEqual(field.active(), 0, "nothing is tracked after");
        helper.assertTrue(!field.plant(a, 0), "a closed field plants nothing");
        helper.succeed();
    }

    @GameTest
    public void aGnawedRoomSeedsDeterministicallyAndGrowsWhenEntered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(0, 1, 0));
        // A floor row and nothing above it: the whole interior is open ground.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(cell.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(level, cell.getX() + 40.5, cell.getY() + 1, cell.getZ() + 40.5, Set.of(), 0.0F, 0.0F,
                    false);
            CreepingBricks.armRoom(level, cell, cell, "pocketdungeons:infested_wall", "infestation", 99L);
            helper.assertTrue(CreepingBricks.seedsOf(cell).isEmpty(), "the Lush Caves wall grows no bricks");
            CreepingBricks.armRoom(level, cell, cell, "pocketdungeons:gnawed_vein", "mineshaft", 99L);
            helper.assertTrue(CreepingBricks.seedsOf(cell).isEmpty(), "another theme grows no bricks");

            CreepingBricks.armRoom(level, cell, cell, "pocketdungeons:gnawed_vein", "infestation", 99L);
            List<BlockPos> seeds = CreepingBricks.seedsOf(cell);
            helper.assertValueEqual(seeds.size(), PocketDungeonsConfig.creepingRoomSeeds(), "the knob's seed count");
            for (BlockPos seed : seeds) {
                helper.assertTrue(!CreepingRules.inDoorLane(seed.getX() - cell.getX(), seed.getZ() - cell.getZ()),
                        "a seed never sits on a door lane");
            }
            CreepingBricks.armRoom(level, cell, cell, "pocketdungeons:gnawed_vein", "infestation", 99L);
            helper.assertValueEqual(CreepingBricks.seedsOf(cell), seeds, "the same plan seed marks the same spots");

            CreepingBricks.pollForTest(1000);
            helper.assertTrue(level.getBlockState(seeds.get(0)).isAir(), "nobody inside: nothing grows");

            player.teleportTo(level, cell.getX() + 8.5, cell.getY() + 1, cell.getZ() + 8.5, Set.of(), 0.0F, 0.0F,
                    false);
            // Poll in small steps and note every seed that ever grows: the start delays depend on the plan seed
            // (which mixes in the test's world position), so a single late poll can find an early seed already gone.
            Set<BlockPos> grown = new java.util.HashSet<>();
            for (long now = 2000; now <= 2000 + CreepingRules.SEED_GAP_TICKS * 8L; now += 5) {
                CreepingBricks.pollForTest(now);
                for (BlockPos seed : seeds) {
                    if (!level.getBlockState(seed).isAir()) {
                        grown.add(seed);
                    }
                }
            }
            helper.assertValueEqual(grown.size(), seeds.size(), "a member inside starts every seed");

            CreepingBricks.clear(cell);
            for (BlockPos seed : seeds) {
                helper.assertTrue(level.getBlockState(seed).isAir(), "clearing the cell restores the room");
            }
        } finally {
            CreepingBricks.clear(cell);
            player.discard();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    level.setBlock(cell.offset(x, 0, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        helper.succeed();
    }
}
