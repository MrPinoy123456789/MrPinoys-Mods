package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.PistonType;

/**
 * M64 (SITUATIONS_SPEC 12.3, 13.4): block-level handler tests for the gated
 * rooms, the hazard rooms, and the return-path validator.
 *
 * <p>Each handler follows the same lifecycle: arm at stamp time, evaluate on a
 * slow server tick, clear on teardown. These scenarios exercise that lifecycle
 * directly in a live world, placing the blocks a template would place and
 * driving the handler through its tick.
 *
 * <p>What is here and what is not: the handlers' tick methods are private and
 * wired through {@code ServerTickEvents}, so the scenarios wait for the server
 * tick to reach them rather than calling tick directly. That is the real path
 * and the one that can break. Block placement uses {@code level.setBlock}
 * directly because the default 8x8 gametest structure is too small for a
 * 16x16 cell; the structure is the trigger, not the stage.
 *
 * <p>Same package rationale as {@link CustodyGameTest}: the handlers are
 * package-private.
 */
public final class HandlerGameTest {

    /** Ticks to wait for Locks.tick (period 10) to evaluate at least once. */
    private static final long LOCK_WAIT = 25L;
    /** Ticks to wait for RisingLavaOrdeal (through Ordeals.tick) (period 2) to evaluate. */
    private static final long LAVA_WAIT = 15L;
    /** Ticks to wait for CollapsingBridgeOrdeal (through Ordeals.tick) (period 2) to evaluate. */
    private static final long BRIDGE_WAIT = 15L;

    // ---- Locks: ITEM_KEY ----

    /**
     * A Frame Lock gate: the door opens when the correct key item is placed in
     * the chest, and stays closed for any other item. This is the
     * {@link Locks.Kind#ITEM_KEY} path.
     */
    @GameTest(maxTicks = 100)
    public void locksItemKeyOpensForCorrectItemOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos doorLocal = new BlockPos(1, 1, 1);
        BlockPos chestLocal = new BlockPos(3, 1, 1);
        BlockPos doorPos = helper.absolutePos(doorLocal);
        BlockPos chestPos = helper.absolutePos(chestLocal);

        placeDoor(level, doorPos);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);

        Locks.arm(level, origin, Locks.Kind.ITEM_KEY, Items.STICK);
        helper.assertTrue(Locks.isArmed(origin), "the ITEM_KEY lock armed");

        // Wrong item: door stays closed.
        placeItem(level, chestPos, new ItemStack(Items.STONE, 1));
        helper.runAfterDelay(LOCK_WAIT, () -> {
            helper.assertFalse(isDoorOpen(level, doorPos),
                    "the door stayed closed for the wrong item");
            removeItems(level, chestPos);

            // Correct item: door opens.
            placeItem(level, chestPos, new ItemStack(Items.STICK, 1));
            helper.runAfterDelay(LOCK_WAIT, () -> {
                helper.assertTrue(isDoorOpen(level, doorPos),
                        "the door opened for the correct key item");
                Locks.clear(origin);
                helper.succeed();
            });
        });
    }

    // ---- Locks: stale cleanup ----

    /**
     * A lock whose door has been torn down is stale and must be purged from the
     * ACTIVE map. Without this, a restamped cell would carry a ghost lock that
     * no longer matches the world.
     */
    @GameTest(maxTicks = 100)
    public void locksStalePurgesWhenDoorRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos doorPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 1));

        placeDoor(level, doorPos);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        Locks.arm(level, origin, Locks.Kind.ITEM_ANY, null);
        helper.assertTrue(Locks.isArmed(origin), "the lock armed");

        // Remove the door: the lock is now stale.
        level.setBlock(doorPos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(doorPos.above(), Blocks.AIR.defaultBlockState(), 3);

        helper.runAfterDelay(LOCK_WAIT, () -> {
            helper.assertFalse(Locks.isArmed(origin),
                    "the stale lock was purged when its door disappeared");
            Locks.clear(origin);
            helper.succeed();
        });
    }

    // ---- Locks: duplicate arming ----

    /**
     * Arming a lock over a cell that already has one replaces it. A restamp
     * writes new blocks, so the old lock's scanned positions would be wrong;
     * the new scan is what the cell needs.
     */
    @GameTest(maxTicks = 100)
    public void locksDuplicateArmingReplacesLock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos doorPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 1));

        placeDoor(level, doorPos);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);

        Locks.arm(level, origin, Locks.Kind.ITEM_ANY, null);
        helper.assertTrue(Locks.isArmed(origin), "first arm took");

        // Re-arm: the old lock is replaced.
        Locks.arm(level, origin, Locks.Kind.ITEM_KEY, Items.STICK);
        helper.assertTrue(Locks.isArmed(origin), "second arm replaced the first");

        // The new lock is ITEM_KEY: a non-key item should not open the door.
        placeItem(level, chestPos, new ItemStack(Items.STONE, 1));
        helper.runAfterDelay(LOCK_WAIT, () -> {
            helper.assertFalse(isDoorOpen(level, doorPos),
                    "the replaced lock is ITEM_KEY; a non-key item does not open it");
            Locks.clear(origin);
            helper.succeed();
        });
    }

    // ---- Locks: slot reuse after clear ----

    /**
     * After a cell is cleared (teardown), arming it again works. A slot that
     * cannot be reused is a slot that leaks.
     */
    @GameTest(maxTicks = 100)
    public void locksSlotReuseAfterClear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos doorPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 1));

        placeDoor(level, doorPos);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);

        Locks.arm(level, origin, Locks.Kind.ITEM_ANY, null);
        helper.assertTrue(Locks.isArmed(origin), "first arm took");
        Locks.clear(origin);
        helper.assertFalse(Locks.isArmed(origin), "clear dropped the lock");

        // Re-arm after clear: the slot is reused.
        Locks.arm(level, origin, Locks.Kind.ITEM_ANY, null);
        helper.assertTrue(Locks.isArmed(origin), "re-arm after clear took");

        placeItem(level, chestPos, new ItemStack(Items.STICK, 1));
        helper.runAfterDelay(LOCK_WAIT, () -> {
            helper.assertTrue(isDoorOpen(level, doorPos),
                    "the reused slot opened the door");
            Locks.clear(origin);
            helper.succeed();
        });
    }

    // ---- RisingLavaOrdeal: lever drain ----

    /**
     * Pulling the lever drains all lava and marks the room solved. The handler
     * then drops itself from the ACTIVE map. This is the room's exit path: the
     * player pulls the lever, the lava goes, the room is safe.
     */
    @GameTest(maxTicks = 100)
    public void risingLavaLeverDrainsRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos leverPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos lavaPos = helper.absolutePos(new BlockPos(5, 1, 5));

        // Place a lever and some lava.
        level.setBlock(leverPos, Blocks.LEVER.defaultBlockState(), 3);
        level.setBlock(lavaPos, Blocks.LAVA.defaultBlockState(), 3);
        Ordeals.arm(RisingLavaOrdeal.INSTANCE, level, origin);
        helper.assertTrue(Ordeals.isActive(RisingLavaOrdeal.INSTANCE, origin), "the lava hazard armed");

        // Pull the lever: powered = true.
        BlockState leverState = level.getBlockState(leverPos);
        level.setBlock(leverPos, leverState.setValue(BlockStateProperties.POWERED, true), 3);

        helper.runAfterDelay(LAVA_WAIT, () -> {
            helper.assertFalse(level.getBlockState(lavaPos).is(Blocks.LAVA),
                    "the lava was drained when the lever was pulled");
            helper.assertFalse(Ordeals.isActive(RisingLavaOrdeal.INSTANCE, origin),
                    "the solved room dropped itself from the active map");
            Ordeals.clear(origin);
            helper.succeed();
        });
    }

    // ---- RisingLavaOrdeal: stale cleanup ----

    /**
     * A lava room whose lever has been torn down is stale and must be purged.
     * Without this, a restamped cell would carry a ghost hazard.
     */
    @GameTest(maxTicks = 100)
    public void risingLavaStalePurgesWhenLeverRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos leverPos = helper.absolutePos(new BlockPos(1, 1, 1));

        level.setBlock(leverPos, Blocks.LEVER.defaultBlockState(), 3);
        Ordeals.arm(RisingLavaOrdeal.INSTANCE, level, origin);
        helper.assertTrue(Ordeals.isActive(RisingLavaOrdeal.INSTANCE, origin), "the lava hazard armed");

        // Remove the lever: the room is stale.
        level.setBlock(leverPos, Blocks.AIR.defaultBlockState(), 3);

        helper.runAfterDelay(LAVA_WAIT, () -> {
            helper.assertFalse(Ordeals.isActive(RisingLavaOrdeal.INSTANCE, origin),
                    "the stale lava hazard was purged when its lever disappeared");
            Ordeals.clear(origin);
            helper.succeed();
        });
    }

    // ---- CollapsingBridgeOrdeal: stale cleanup ----

    /**
     * A bridge whose pistons have been torn down is stale and must be purged.
     */
    @GameTest(maxTicks = 100)
    public void collapsingBridgeStalePurgesWhenPistonRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        // Two sticky pistons facing each other with a 2-block gap between planks.
        // Layout: P(3) head(4) plank(5) gap(6) plank(7) head(8) P(9)
        BlockPos pistonA = helper.absolutePos(new BlockPos(1, 1, 3));
        BlockPos pistonB = helper.absolutePos(new BlockPos(1, 1, 9));

        level.setBlock(pistonA, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.SOUTH), 3);
        level.setBlock(pistonB, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.NORTH), 3);
        level.setBlock(pistonA.south(), Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE, PistonType.STICKY), 3);
        level.setBlock(pistonB.north(), Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE, PistonType.STICKY), 3);
        level.setBlock(pistonA.south().south(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        level.setBlock(pistonB.north().north(), Blocks.OAK_PLANKS.defaultBlockState(), 3);

        Ordeals.arm(CollapsingBridgeOrdeal.INSTANCE, level, origin);
        helper.assertTrue(Ordeals.isActive(CollapsingBridgeOrdeal.INSTANCE, origin), "the bridge armed");

        // Remove the pistons: the bridge is stale.
        level.setBlock(pistonA, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pistonB, Blocks.AIR.defaultBlockState(), 3);

        helper.runAfterDelay(BRIDGE_WAIT, () -> {
            helper.assertFalse(Ordeals.isActive(CollapsingBridgeOrdeal.INSTANCE, origin),
                    "the stale bridge was purged when its pistons disappeared");
            Ordeals.clear(origin);
            helper.succeed();
        });
    }

    // ---- CollapsingBridgeOrdeal: duplicate arming ----

    /**
     * Arming a bridge over a cell that already has one replaces it. A restamp
     * writes new blocks, so the old bridge's scanned positions would be wrong.
     */
    @GameTest(maxTicks = 100)
    public void collapsingBridgeDuplicateArmingReplacesBridge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos pistonA = helper.absolutePos(new BlockPos(1, 1, 3));
        BlockPos pistonB = helper.absolutePos(new BlockPos(1, 1, 9));

        level.setBlock(pistonA, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.SOUTH), 3);
        level.setBlock(pistonB, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.NORTH), 3);
        level.setBlock(pistonA.south(), Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE, PistonType.STICKY), 3);
        level.setBlock(pistonB.north(), Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE, PistonType.STICKY), 3);
        level.setBlock(pistonA.south().south(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        level.setBlock(pistonB.north().north(), Blocks.OAK_PLANKS.defaultBlockState(), 3);

        Ordeals.arm(CollapsingBridgeOrdeal.INSTANCE, level, origin);
        helper.assertTrue(Ordeals.isActive(CollapsingBridgeOrdeal.INSTANCE, origin), "first arm took");
        // Re-arm: the old bridge is replaced.
        Ordeals.arm(CollapsingBridgeOrdeal.INSTANCE, level, origin);
        helper.assertTrue(Ordeals.isActive(CollapsingBridgeOrdeal.INSTANCE, origin), "second arm replaced the first");
        Ordeals.clear(origin);
        helper.succeed();
    }

    // ---- ReturnPathValidator: ladder column ----

    /**
     * A ladder column from the lower floor to the upper floor validates. This
     * is the simplest return path: a vertical shaft of ladder blocks with an
     * open shaft through the over-ceiling filler.
     */
    @GameTest(maxTicks = 50)
    public void returnPathLadderColumnValidates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Place the cell origin high enough that the lower story fits below.
        // storyOffset(2) = 8, so the lower floor is at y = -8 relative.
        BlockPos origin = helper.absolutePos(new BlockPos(0, 20, 0));
        int lowerFloorY = -8;

        // Build a floor at the lower level and a ladder column at (5, *, 5).
        level.setBlock(origin.offset(5, lowerFloorY, 5), Blocks.STONE.defaultBlockState(), 3);
        for (int y = lowerFloorY + 1; y <= -1; y++) {
            level.setBlock(origin.offset(5, y, 5), Blocks.LADDER.defaultBlockState(), 3);
        }

        helper.assertTrue(ReturnPathValidator.validate(level, origin, 2),
                "a ladder column from the lower floor to the upper floor validates");
        helper.succeed();
    }

    // ---- ReturnPathValidator: water column ----

    /**
     * A water source column from the lower floor to the upper floor validates.
     * The player can swim up.
     */
    @GameTest(maxTicks = 50)
    public void returnPathWaterColumnValidates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 20, 0));
        int lowerFloorY = -8;

        level.setBlock(origin.offset(5, lowerFloorY, 5), Blocks.STONE.defaultBlockState(), 3);
        for (int y = lowerFloorY + 1; y <= -1; y++) {
            level.setBlock(origin.offset(5, y, 5), Blocks.WATER.defaultBlockState(), 3);
        }

        helper.assertTrue(ReturnPathValidator.validate(level, origin, 2),
                "a water column from the lower floor to the upper floor validates");
        helper.succeed();
    }

    // ---- ReturnPathValidator: staircase with headroom ----

    /**
     * A staircase of solid blocks from the lower floor to the upper floor
     * validates, as long as each step has headroom (the block above is air).
     * This is the flood-fill path: the validator walks up step by step.
     */
    @GameTest(maxTicks = 50)
    public void returnPathStaircaseWithHeadroomValidates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 20, 0));
        int lowerFloorY = -8;

        // Build a floor at the lower level.
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            level.setBlock(origin.offset(x, lowerFloorY, 5), Blocks.STONE.defaultBlockState(), 3);
        }
        // Build a staircase: each step is one block higher, walking in +x.
        for (int step = 0; step <= 8; step++) {
            int y = lowerFloorY + step;
            int x = 1 + step;
            if (x >= RoomGeometry.CELL - 1 || y > 0) {
                break;
            }
            level.setBlock(origin.offset(x, y, 5), Blocks.STONE.defaultBlockState(), 3);
            // Headroom: the block above must be air (it is by default).
        }

        helper.assertTrue(ReturnPathValidator.validate(level, origin, 2),
                "a staircase with headroom from the lower floor to the upper floor validates");
        helper.succeed();
    }

    // ---- ReturnPathValidator: missing route fails ----

    /**
     * A room with no climbable route from the lower floor to the upper floor
     * does not validate. This is the authoring mistake the validator exists to
     * catch: a lower story with no way out.
     */
    @GameTest(maxTicks = 50)
    public void returnPathMissingRouteFails(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 20, 0));
        int lowerFloorY = -8;

        // Build a floor at the lower level, but no climbable route.
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            level.setBlock(origin.offset(x, lowerFloorY, 5), Blocks.STONE.defaultBlockState(), 3);
        }
        // Fill the over-ceiling filler with solid blocks so no shaft exists.
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            level.setBlock(origin.offset(x, -2, 5), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(origin.offset(x, -1, 5), Blocks.STONE.defaultBlockState(), 3);
        }

        helper.assertFalse(ReturnPathValidator.validate(level, origin, 2),
                "a room with no climbable route does not validate");
        helper.succeed();
    }

    // ---- PD-86: the traversal family's classic spawners ----

    /**
     * PD-86: the traversal handlers are registered on a live server, not only
     * after {@code /dungeon admin gentemplates} has touched the class. Before
     * the fix a thicket cell fell through to the corridor dispatch and its
     * baked zombie spawner never fired.
     */
    @GameTest(maxTicks = 20)
    public void traversalHandlersAreRegistered(GameTestHelper helper) {
        for (String id : new String[] {"thicket", "ice_run", "flooded_hall", "chasm", "powder_snow_field"}) {
            helper.assertTrue(Situations.isRegistered(id), "situation " + id + " is registered at boot");
        }
        helper.succeed();
    }

    /**
     * PD-86: a thicket stamp turns the baked zombie spawner into a cave spider
     * spawner with light-free custom spawn rules, which is what lets it fire
     * in a sea-lantern-lit room.
     */
    @GameTest(maxTicks = 20)
    public void thicketSpawnerBecomesCaveSpider(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos spawnerPos = origin.offset(3, 1, 3);
        level.setBlock(spawnerPos, Blocks.SPAWNER.defaultBlockState(), 3);
        if (level.getBlockEntity(spawnerPos) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity spawner) {
            spawner.setEntityId(net.minecraft.world.entity.EntityTypes.ZOMBIE, level.getRandom());
        }
        Situations.apply(level, origin, "corridor", 0, null, java.util.List.of(), 0L,
                java.util.Set.of(), "", null, false, "thicket");
        var tag = level.getBlockEntity(spawnerPos).saveWithoutMetadata(level.registryAccess());
        var spawnData = tag.getCompoundOrEmpty("SpawnData");
        helper.assertTrue("minecraft:cave_spider".equals(
                        spawnData.getCompoundOrEmpty("entity").getStringOr("id", "")),
                "the thicket spawner spawns cave spiders: " + spawnData);
        helper.assertTrue(spawnData.contains("custom_spawn_rules"),
                "the thicket spawner carries light-free custom spawn rules: " + spawnData);
        helper.succeed();
    }

    // ---- helpers ----

    private static void placeDoor(ServerLevel level, BlockPos doorLower) {
        level.setBlock(doorLower, Blocks.IRON_DOOR.defaultBlockState(), 3);
        level.setBlock(doorLower.above(), Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
    }

    private static void placeItem(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            container.setItem(0, stack);
        }
    }

    private static void removeItems(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            container.clearContent();
        }
    }

    private static boolean isDoorOpen(ServerLevel level, BlockPos doorLower) {
        return level.getBlockState(doorLower).is(Blocks.IRON_DOOR)
                && level.getBlockState(doorLower).getValue(DoorBlock.OPEN);
    }
}
