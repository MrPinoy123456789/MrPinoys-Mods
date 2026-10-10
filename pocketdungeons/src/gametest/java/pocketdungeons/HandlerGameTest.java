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

    // ---- CollapsingBridgeOrdeal: real template ----

    /**
     * PD-123: the collapsing_bridge template can be captured while retracting,
     * which stores {@code moving_piston} blocks instead of sticky pistons.
     * Arming must still find the pistons, normalise them and extend the bridge.
     */
    @GameTest(maxTicks = 100)
    public void collapsingBridgeTemplateArmsAtEveryRotation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int q = 0; q < 4; q++) {
            BlockPos origin = new BlockPos(4096 + q * 2 * RoomGeometry.CELL, 120, 4096);
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
            try {
                TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                        net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/collapsing_bridge"), q, 1L);
                Ordeals.arm(CollapsingBridgeOrdeal.INSTANCE, level, origin);
                helper.assertTrue(Ordeals.isActive(CollapsingBridgeOrdeal.INSTANCE, origin),
                        "collapsing_bridge armed at rotation " + q);
                Ordeals.clear(origin);
            } finally {
                level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            }
        }
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

    /**
     * PD-78: the baked {@code blaze_cellar} template, stamped the way a run
     * stamps it, has a return path at every rotation. Its old "staircase"
     * was a solid pillar, and every run that rolled the room failed to build.
     */
    @GameTest(maxTicks = 50)
    public void blazeCellarTemplateHasAReturnPath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int q = 0; q < 4; q++) {
            BlockPos origin = new BlockPos(4096 + q * 2 * RoomGeometry.CELL, 120, 4096);
            for (int dx = 0; dx < 2 * RoomGeometry.CELL; dx += 16) {
                level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, true);
            }
            try {
                TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                        net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/blaze_cellar"), q, 1L);
                helper.assertTrue(ReturnPathValidator.validate(level, origin, 2),
                        "blaze_cellar has a climbable return path at " + q + " quarter turns");
            } finally {
                for (int dx = 0; dx < 2 * RoomGeometry.CELL; dx += 16) {
                    level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, false);
                }
            }
        }
        helper.succeed();
    }

    /**
     * PD-97: the baked {@code sump} template has a return path at every
     * rotation. Its old "staircase" was a solid pillar, like PD-78's
     * {@code blaze_cellar}, and every run that rolled the room failed to build.
     */
    @GameTest(maxTicks = 50)
    public void sumpTemplateHasAReturnPath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int q = 0; q < 4; q++) {
            BlockPos origin = new BlockPos(4096 + q * 2 * RoomGeometry.CELL, 120, 4096 + 4 * RoomGeometry.CELL);
            for (int dx = 0; dx < 2 * RoomGeometry.CELL; dx += 16) {
                level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, true);
            }
            try {
                TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                        net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/sump"), q, 1L);
                helper.assertTrue(ReturnPathValidator.validate(level, origin, 2),
                        "sump has a climbable return path at " + q + " quarter turns");
            } finally {
                for (int dx = 0; dx < 2 * RoomGeometry.CELL; dx += 16) {
                    level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, false);
                }
            }
        }
        helper.succeed();
    }

    /**
     * Two-story Ordeal rooms (2026-10-02): each of the three, stamped at a
     * quarter turn and sealed, has rubble over every hole in its upper floor,
     * hides its lower story's spawners from the clear gate, and after a blast
     * is open again with its ladder's top rung back. (The return-path check
     * reads only below the upper floor, so it passes either way; the holes
     * are checked directly.)
     */
    @GameTest(maxTicks = 80)
    public void sealedTwoStoryRoomsOpenToABlast(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String[] rooms = {"blaze_cellar", "slime_pit", "sump"};
        java.util.List<BlockPos> origins = new java.util.ArrayList<>();
        java.util.List<java.util.List<BlockPos>> holesByRoom = new java.util.ArrayList<>();
        for (int r = 0; r < rooms.length; r++) {
            BlockPos origin = new BlockPos(4096 + r * 2 * RoomGeometry.CELL, 120, 4096 + 7 * RoomGeometry.CELL);
            origins.add(origin);
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
            TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                    net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/" + rooms[r]), 1, 1L);
            helper.assertTrue(ReturnPathValidator.validate(level, origin, 2), rooms[r] + " starts open");
            java.util.List<BlockPos> holes = new java.util.ArrayList<>();
            for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
                for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                    BlockPos top = origin.offset(x, 0, z);
                    if (open(level, top) && open(level, top.below())) {
                        holes.add(top);
                    }
                }
            }
            holesByRoom.add(holes);
            // slime_pit's only opening is its ladder (8, 1): the slime is a floor, not a
            // shaft. The other two rooms have a shaft as well.
            int openings = "slime_pit".equals(rooms[r]) ? 1 : 2;
            helper.assertTrue(holes.size() >= openings, rooms[r] + " has its way down, found " + holes);
            RubbleOrdeal.sealFloor(level, origin);
            Ordeals.armAt(RubbleOrdeal.FLOOR, level, origin, origin.below());
            for (BlockPos hole : holes) {
                helper.assertTrue(RubbleOrdeal.isArmedRubble(level, hole), rooms[r] + " sealed the hole at " + hole);
            }
            BlockPos first = holes.get(0);
            helper.assertTrue(RubbleOrdeal.hidesSpawner(level, origin.offset(8, -8, 8)),
                    rooms[r] + "'s lower story is hidden from the clear gate");
            helper.assertTrue(RubbleOrdeal.blast(level, net.minecraft.world.phys.Vec3.atCenterOf(first).add(0, 2, 0),
                    3.0f), rooms[r] + " is reached by a blast over its rubble");
        }
        helper.runAfterDelay(30, () -> {
            try {
                for (int r = 0; r < rooms.length; r++) {
                    BlockPos origin = origins.get(r);
                    for (BlockPos hole : holesByRoom.get(r)) {
                        helper.assertTrue(open(level, hole), rooms[r] + " is open again at " + hole);
                        if (level.getBlockState(hole.below()).is(net.minecraft.world.level.block.Blocks.LADDER)) {
                            helper.assertTrue(level.getBlockState(hole).is(net.minecraft.world.level.block.Blocks.LADDER),
                                    rooms[r] + " has its ladder's top rung back at " + hole);
                        }
                    }
                    helper.assertTrue(ReturnPathValidator.validate(level, origin, 2),
                            rooms[r] + " has its return path after the blast");
                    helper.assertFalse(RubbleOrdeal.hidesSpawner(level, origin.offset(8, -8, 8)),
                            rooms[r] + "'s lower story counts again");
                }
                helper.succeed();
            } finally {
                for (BlockPos origin : origins) {
                    Ordeals.clear(origin);
                    level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
                }
            }
        });
    }

    /**
     * PD-98: each situation combat room, stamped from its baked template the
     * way a run stamps it and then handed to its situation handler, ends up
     * with a configured trial spawner. {@code kennel_crossing} once stamped
     * with none because the handler found no anchor.
     */
    @GameTest(maxTicks = 100)
    public void situationCombatRoomsStampATrialSpawner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        java.util.List<String> failures = new java.util.ArrayList<>();
        int row = 0;
        for (String id : new String[]{"sensor_gallery", "kennel_crossing", "blaze_cellar"}) {
            for (int q = 0; q < 4; q++) {
                BlockPos origin = new BlockPos(6144 + q * 2 * RoomGeometry.CELL, 120,
                        6144 + row * 3 * RoomGeometry.CELL);
                for (int dx = 0; dx < 2 * RoomGeometry.CELL; dx += 16) {
                    level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, true);
                }
                try {
                    java.util.List<BlockPos> spawns = TemplateStamper.place(level,
                            level.getServer().getStructureManager(), origin,
                            net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/" + id),
                            q, 1L, net.minecraft.resources.Identifier.parse("pocketdungeons:theme_deepslate"));
                    BlockPos anchor = Situations.apply(level, origin, "encounter", 1,
                            DifficultyProfile.of(5, 1), spawns, 1L, java.util.Set.of(), "", null,
                            false, id);
                    if (anchor == null || !(level.getBlockEntity(anchor)
                            instanceof net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity)) {
                        failures.add(id + " at " + q + " quarter turns placed no trial spawner");
                    }
                } finally {
                    for (int dx = 0; dx < 2 * RoomGeometry.CELL; dx += 16) {
                        level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, false);
                    }
                }
            }
            row++;
        }
        helper.assertTrue(failures.isEmpty(), "situation combat rooms: " + failures);
        helper.succeed();
    }

    /**
     * PD-98: the kennel's wolves can only spawn on {@code WOLVES_SPAWNABLE_ON}
     * blocks (a trial spawner runs the mob's own placement rules), so the pen
     * floor is grass and the spawner's range stays inside the pen. At every
     * rotation, several free spots around the spawner must sit on wolf ground.
     */
    @GameTest(maxTicks = 100)
    public void kennelPenLetsWolvesSpawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int q = 0; q < 4; q++) {
            BlockPos origin = new BlockPos(6144 + q * 2 * RoomGeometry.CELL, 120, 6144 + 5 * 3 * RoomGeometry.CELL);
            for (int dx = 0; dx < 2 * RoomGeometry.CELL; dx += 16) {
                level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, true);
            }
            try {
                java.util.List<BlockPos> spawns = TemplateStamper.place(level,
                        level.getServer().getStructureManager(), origin,
                        net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/kennel_crossing"),
                        q, 1L, net.minecraft.resources.Identifier.parse("pocketdungeons:theme_deepslate"));
                BlockPos anchor = Situations.apply(level, origin, "encounter", 1,
                        DifficultyProfile.of(5, 1), spawns, 1L, java.util.Set.of(), "", null,
                        false, "kennel_crossing");
                helper.assertTrue(anchor != null, "kennel_crossing placed a spawner at " + q + " quarter turns");
                int spots = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos pos = anchor.offset(dx, 0, dz);
                        if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                                && level.getBlockState(pos.below())
                                        .is(net.minecraft.tags.BlockTags.WOLVES_SPAWNABLE_ON)) {
                            spots++;
                        }
                    }
                }
                helper.assertTrue(spots >= 3, "the kennel pen has " + spots
                        + " wolf spawn spots at " + q + " quarter turns, wanted at least 3");
            } finally {
                for (int dx = 0; dx < 2 * RoomGeometry.CELL; dx += 16) {
                    level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, false);
                }
            }
        }
        helper.succeed();
    }

    /**
     * PD-99: {@code rotation_lock} opens when its frame is on position 8 and
     * stays shut otherwise, at every room rotation. The comparator and the
     * door repeater used to face the wrong way round, so the redstone never
     * carried the frame's signal to the door.
     */
    @GameTest(maxTicks = 300)
    public void rotationLockSolvesAtEveryRotation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cell = RoomGeometry.CELL;
        BlockPos[] origins = new BlockPos[8];
        boolean[] solved = new boolean[8];
        for (int i = 0; i < 8; i++) {
            solved[i] = i % 2 == 0;
            origins[i] = new BlockPos(8192 + i * 2 * cell, 120, 8192);
            for (int dx = 0; dx < 2 * cell; dx += 16) {
                level.setChunkForced((origins[i].getX() + dx) >> 4, origins[i].getZ() >> 4, true);
            }
        }
        // Entities (the frame) need the chunks fully loaded before the stamp.
        helper.runAfterDelay(20, () -> {
            for (int i = 0; i < 8; i++) {
                int q = i / 2;
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(origins[i])
                        .expandTowards(cell, 6, cell);
                // The test world persists between runs: clear frames a past run left behind.
                for (net.minecraft.world.entity.decoration.ItemFrame old : level.getEntitiesOfClass(
                        net.minecraft.world.entity.decoration.ItemFrame.class, box)) {
                    old.discard();
                }
                TemplateStamper.place(level, level.getServer().getStructureManager(), origins[i],
                        net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/rotation_lock"), q, 1L);
                java.util.List<net.minecraft.world.entity.decoration.ItemFrame> frames =
                        level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, box);
                helper.assertTrue(frames.size() == 1, "rotation_lock has one item frame at " + q
                        + " quarter turns, found " + frames.size());
                if (solved[i]) {
                    frames.get(0).setRotation(7);
                }
            }
        });
        helper.runAfterDelay(100, () -> {
            java.util.List<String> failures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int open = 0;
                for (int x = 0; x < cell; x++) {
                    for (int y = 1; y <= 3; y++) {
                        for (int z = 0; z < cell; z++) {
                            BlockState state = level.getBlockState(origins[i].offset(x, y, z));
                            if (state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.OPEN)) {
                                open++;
                            }
                        }
                    }
                }
                if (solved[i] != (open > 0)) {
                    failures.add("q" + (i / 2) + (solved[i] ? " solved" : " unsolved")
                            + " has " + open + " open door blocks");
                }
            }
            for (BlockPos origin : origins) {
                for (int dx = 0; dx < 2 * cell; dx += 16) {
                    level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, false);
                }
            }
            helper.assertTrue(failures.isEmpty(), "rotation_lock: " + failures);
            helper.succeed();
        });
    }

    /**
     * PD-133 (playtest 2026-10-03-2): every gated room can be entered from the
     * cell it is approached from. The party walked out of the entrance hall
     * into a rotation_lock whose iron door faced them, because the selector
     * kept whichever of the two straight rotations it rolled.
     *
     * <p>For each gated room in the live manifest, each of the four approach
     * directions (every straight neighbour mask, both ways round) and both
     * rotations that fit the mask, {@link RoomSelector#orientGatedRooms}
     * picks the rotation; the room is stamped at it and the entry lane (the
     * doorway and three blocks in, two tall) must be walkable, and every iron
     * door must stand in the half of the room away from the approach.
     */
    @GameTest(maxTicks = 200)
    public void gatedRoomsCanBeEnteredFromEveryApproach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cell = RoomGeometry.CELL;
        java.util.List<String> failures = new java.util.ArrayList<>();
        int rooms = 0;
        int stamps = 0;
        int row = 0;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (RoomManifest.Entry entry : RoomManifest.current().rooms()) {
            if (!DungeonRoomMeta.ACCESS_GATED.equals(entry.meta.access)) {
                continue;
            }
            rooms++;
            row++;
            int column = 0;
            int before = stamps;
            for (int[] step : steps) {
                PlanCell from = new PlanCell(0, 0);
                PlanCell gate = new PlanCell(step[0], step[1]);
                PlanCell past = new PlanCell(2 * step[0], 2 * step[1]);
                java.util.Set<PlanCell> cells = new java.util.LinkedHashSet<>(java.util.List.of(from, gate, past));
                java.util.Set<PlanEdge> edges = new java.util.LinkedHashSet<>(java.util.List.of(
                        new PlanEdge(from, gate), new PlanEdge(gate, past)));
                java.util.Map<PlanCell, String> roles = new java.util.LinkedHashMap<>();
                roles.put(from, RoleIds.ENTRANCE);
                roles.put(gate, RoleIds.CORRIDOR);
                roles.put(past, RoleIds.EXIT);
                DungeonShape shape = new DungeonShape(1L, cells, edges, from, past,
                        java.util.List.of(from, gate, past), roles);
                java.util.Map<PlanCell, Integer> depths = java.util.Map.of(from, 0, gate, 1, past, 2);
                int straight = DoorMask.fromEdges(java.util.EnumSet.of(
                        gate.directionTo(from), gate.directionTo(past)));
                for (int rolled = 0; rolled < 4; rolled++) {
                    if (entry.maskAtRotation(rolled) != straight) {
                        continue;
                    }
                    java.util.Map<PlanCell, DungeonPlan.PlacedRoom> placed = new java.util.HashMap<>();
                    placed.put(gate, new DungeonPlan.PlacedRoom(entry.name, rolled));
                    RoomSelector.orientGatedRooms(shape, RoomManifest.current(), placed, depths);
                    int q = placed.get(gate).rotation();
                    BlockPos origin = new BlockPos(16384 + column * 2 * cell, 120, 16384 + row * 2 * cell);
                    column++;
                    stamps++;
                    level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
                    try {
                        TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                                net.minecraft.resources.Identifier.parse(entry.meta.template), q, 1L);
                        String label = entry.name + " approached from " + gate.directionTo(from)
                                + " (rolled " + rolled + ", placed " + q + ")";
                        // The entry lane, authored at rotation 0 along the west wall:
                        // at least one of the two doorway columns walkable from the
                        // doorway plane three blocks in (sorting_floor's channel
                        // fences one of them on purpose).
                        java.util.List<String> blocked = new java.util.ArrayList<>();
                        for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
                            String block = null;
                            for (int x = 0; x <= 3 && block == null; x++) {
                                for (int y = 1; y <= 2 && block == null; y++) {
                                    BlockPos pos = origin.offset(rotateLocal(x, z, q, cell)).above(y);
                                    BlockState state = level.getBlockState(pos);
                                    if (state.is(Blocks.IRON_DOOR)
                                            || !state.getCollisionShape(level, pos).isEmpty()) {
                                        block = "local " + x + "," + y + "," + z + " " + state.getBlock();
                                    }
                                }
                            }
                            if (block != null) {
                                blocked.add(block);
                            }
                        }
                        if (blocked.size() == 2) {
                            failures.add(label + ": entry lane blocked at " + blocked);
                        }
                        // Every iron door sits in the far half from the approach.
                        for (int x = 0; x < cell; x++) {
                            for (int z = 0; z < cell; z++) {
                                BlockPos pos = origin.offset(rotateLocal(x, z, q, cell)).above(1);
                                if (x < cell / 2 && level.getBlockState(pos).is(Blocks.IRON_DOOR)) {
                                    failures.add(label + ": iron door on the approach side at local " + x + "," + z);
                                }
                            }
                        }
                    } finally {
                        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
                    }
                }
            }
            if (stamps - before != 8) {
                failures.add(entry.name + " fits a straight cell at " + (stamps - before)
                        + " of 8 approach and rotation pairs (mask "
                        + DoorMask.toLetters(entry.maskAtRotation0) + ")");
            }
        }
        helper.assertTrue(rooms >= 10, "the manifest has its gated rooms (found " + rooms + ")");
        helper.assertTrue(failures.isEmpty(), "gated rooms that cannot be entered: " + failures);
        helper.succeed();
    }

    /**
     * A template-local (x, z) at rotation 0 as an offset from the cell origin
     * once the room is stamped at {@code q} clockwise quarter turns, matching
     * {@link TemplateStamper}'s pivot and per-rotation offsets.
     */
    private static BlockPos rotateLocal(int x, int z, int q, int cell) {
        int m = cell - 1;
        return switch (q) {
            case 1 -> new BlockPos(m - z, 0, x);
            case 2 -> new BlockPos(m - x, 0, m - z);
            case 3 -> new BlockPos(z, 0, m - x);
            default -> new BlockPos(x, 0, z);
        };
    }

    /**
     * PD-137 (playtest 2026-10-03-2): every spur room can be placed. A spur
     * has one door, and the generator gives every dead-end cell the loot
     * role, so a spur authored as {@code corridor} fitted no cell at all:
     * {@code room_bias the_store} x50 across seven floors placed nothing.
     * With the Store biased, it must turn up on most floors that have a
     * spur deep enough for it, and each spur must appear somewhere in an
     * unbiased sweep.
     */
    @GameTest(maxTicks = 200)
    public void everySpurRoomCanBePlaced(GameTestHelper helper) {
        java.util.Map<String, Integer> seen = new java.util.TreeMap<>();
        for (String spur : new String[]{"the_store", "the_altar", "barred_vault", "ominous_bargain"}) {
            seen.put("pocketdungeons:" + spur, 0);
        }
        for (long seed = 1; seed <= 400; seed++) {
            LayoutPlanner.Outcome outcome = LayoutPlanner.plan(seed, RoomManifest.current(),
                    LayoutPlanner.DEFAULT_ATTEMPT_BUDGET,
                    LayoutPlanner.DEFAULT_MIN_PATH, LayoutPlanner.DEFAULT_MAX_PATH,
                    LayoutPlanner.DEFAULT_BRANCH_PROBABILITY, LayoutPlanner.DEFAULT_LOOP_PROBABILITY,
                    LayoutPlanner.DEFAULT_MAX_GRID_SPAN, "deepslate", null);
            if (outcome.plan() == null) {
                continue;
            }
            for (DungeonPlan.PlacedRoom room : outcome.plan().rooms().values()) {
                seen.computeIfPresent(room.name(), (k, v) -> v + 1);
            }
        }
        int biased = 0;
        int floors = 0;
        PlaytestBias.set("the_store", PlaytestBias.MAX_MULTIPLIER);
        try {
            for (long seed = 1; seed <= 100; seed++) {
                LayoutPlanner.Outcome outcome = LayoutPlanner.plan(seed, RoomManifest.current(),
                        LayoutPlanner.DEFAULT_ATTEMPT_BUDGET,
                        LayoutPlanner.DEFAULT_MIN_PATH, LayoutPlanner.DEFAULT_MAX_PATH,
                        LayoutPlanner.DEFAULT_BRANCH_PROBABILITY, LayoutPlanner.DEFAULT_LOOP_PROBABILITY,
                        LayoutPlanner.DEFAULT_MAX_GRID_SPAN, "deepslate", null);
                if (outcome.plan() == null) {
                    continue;
                }
                floors++;
                if (outcome.plan().rooms().values().stream()
                        .anyMatch(r -> r.name().equals("pocketdungeons:the_store"))) {
                    biased++;
                }
            }
        } finally {
            PlaytestBias.set("the_store", 1);
        }
        helper.assertTrue(!seen.containsValue(0), "spur rooms never placed in 400 floors: " + seen);
        helper.assertTrue(biased * 3 >= floors, "the_store x50 placed on " + biased + " of " + floors
                + " floors, wanted at least a third");
        helper.succeed();
    }

    /**
     * PD-134 (playtest 2026-10-03-2): a wolf tamed out of a trial spawner
     * counts as defeated. The spawner waits on every mob it spawned, and a
     * tamed wolf never dies, so the kennel spawner never cleared. A wild
     * wolf in the same spawner is still waited on.
     */
    @GameTest(maxTicks = 40)
    public void tamedWolfIsReleasedFromItsSpawner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, Blocks.TRIAL_SPAWNER.defaultBlockState());
        @SuppressWarnings("removal")
        net.minecraft.server.level.ServerPlayer tamer = helper.makeMockServerPlayerInLevel();
        net.minecraft.world.entity.animal.wolf.Wolf tamed =
                helper.spawn(net.minecraft.world.entity.EntityTypes.WOLF, new BlockPos(3, 1, 3));
        net.minecraft.world.entity.animal.wolf.Wolf wild =
                helper.spawn(net.minecraft.world.entity.EntityTypes.WOLF, new BlockPos(4, 1, 3));
        tamed.tame(tamer);
        var spawner = (net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity) level.getBlockEntity(pos);
        java.util.Set<java.util.UUID> waiting = ((pocketdungeons.mixin.TrialSpawnerStateDataAccessor)
                (Object) spawner.getTrialSpawner().getStateData()).pocketdungeons$currentMobs();
        waiting.add(tamed.getUUID());
        waiting.add(wild.getUUID());
        TrialContent.releaseTamed(level, java.util.Set.of(pos));
        helper.assertFalse(waiting.contains(tamed.getUUID()), "the tamed wolf no longer holds the spawner");
        helper.assertTrue(waiting.contains(wild.getUUID()), "the wild wolf still does");
        helper.succeed();
    }

    /**
     * PD-136 (playtest 2026-10-03-2, second sighting of PD-109): the tripwire
     * hall keeps its six wall dispensers once the corridor role has run. The
     * role's chest removal took every randomizable container, dispensers
     * included, and left holes onto the bedrock envelope. The stamp-only
     * test below never ran the role, so it never saw it.
     */
    @GameTest(maxTicks = 100)
    public void tripwireHallKeepsItsDispensersThroughTheCorridorRole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cell = RoomGeometry.CELL;
        BlockPos origin = new BlockPos(8192, 120, 14336);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        try {
            java.util.List<BlockPos> spawns = TemplateStamper.place(level, level.getServer().getStructureManager(),
                    origin, net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/tripwire_hall"), 1, 1L);
            Situations.apply(level, origin, RoleIds.CORRIDOR, 1, DifficultyProfile.of(5, 1), spawns, 1L,
                    java.util.Set.of(), "", null, false, "tripwire_hall");
            int dispensers = 0;
            for (int x = 0; x < cell; x++) {
                for (int y = 0; y <= 4; y++) {
                    for (int z = 0; z < cell; z++) {
                        if (level.getBlockState(origin.offset(x, y, z)).is(Blocks.DISPENSER)) {
                            dispensers++;
                        }
                    }
                }
            }
            helper.assertValueEqual(dispensers, 6, "dispensers left after the corridor role");
        } finally {
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
        }
        helper.succeed();
    }

    /**
     * PD-109: the tripwire hall's hooks, strings and dispensers survive the
     * template stamp at every rotation (the player saw the traps missing).
     */
    @GameTest(maxTicks = 300)
    public void tripwireHallKeepsItsTrapsAtEveryRotation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cell = RoomGeometry.CELL;
        BlockPos[] origins = new BlockPos[4];
        for (int i = 0; i < 4; i++) {
            origins[i] = new BlockPos(8192 + i * 2 * cell, 120, 12288);
            for (int dx = 0; dx < 2 * cell; dx += 16) {
                level.setChunkForced((origins[i].getX() + dx) >> 4, origins[i].getZ() >> 4, true);
            }
        }
        helper.runAfterDelay(20, () -> {
            for (int q = 0; q < 4; q++) {
                TemplateStamper.place(level, level.getServer().getStructureManager(), origins[q],
                        net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/tripwire_hall"), q, 1L);
            }
        });
        helper.runAfterDelay(60, () -> {
            java.util.List<String> failures = new java.util.ArrayList<>();
            for (int q = 0; q < 4; q++) {
                int hooks = 0, strings = 0, dispensers = 0;
                for (int x = 0; x < cell; x++) {
                    for (int y = 0; y <= 4; y++) {
                        for (int z = 0; z < cell; z++) {
                            BlockState state = level.getBlockState(origins[q].offset(x, y, z));
                            if (state.is(Blocks.TRIPWIRE_HOOK)) {
                                hooks++;
                            } else if (state.is(Blocks.TRIPWIRE)) {
                                strings++;
                            } else if (state.is(Blocks.DISPENSER)) {
                                dispensers++;
                            }
                        }
                    }
                }
                if (hooks != 6 || strings != 36 || dispensers != 6) {
                    failures.add("q" + q + ": hooks " + hooks + "/6, string " + strings + "/36, dispensers "
                            + dispensers + "/6");
                }
            }
            for (BlockPos origin : origins) {
                for (int dx = 0; dx < 2 * cell; dx += 16) {
                    level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, false);
                }
            }
            helper.assertTrue(failures.isEmpty(), "tripwire_hall traps missing: " + failures);
            helper.succeed();
        });
    }

    /**
     * PD-103: the librarian sweep's scan covers the staging room. A
     * librarian past the doorway, outside the old 12 block box, is still
     * found, so the sweep anchors it instead of spawning a second one.
     */
    @GameTest(maxTicks = 100)
    public void librarianSweepSeesIntoTheStagingRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos roomOrigin = new BlockPos(10240, 120, 10240);
        for (int dx = -16; dx <= 2 * RoomGeometry.CELL; dx += 16) {
            level.setChunkForced((roomOrigin.getX() + dx) >> 4, roomOrigin.getZ() >> 4, true);
        }
        helper.runAfterDelay(20, () -> {
            net.minecraft.world.entity.npc.villager.Villager villager =
                    net.minecraft.world.entity.EntityTypes.VILLAGER.create(level,
                            net.minecraft.world.entity.EntitySpawnReason.EVENT);
            villager.setPos(roomOrigin.getX() + RoomGeometry.CELL / 2.0 + 18, roomOrigin.getY() + 1,
                    roomOrigin.getZ() + RoomGeometry.CELL / 2.0);
            villager.addTag(LibrarianNPC.LIBRARIAN_TAG);
            level.addFreshEntity(villager);
            boolean found = LibrarianNPC.findAllLibrarians(level, roomOrigin).contains(villager);
            villager.discard();
            for (int dx = -16; dx <= 2 * RoomGeometry.CELL; dx += 16) {
                level.setChunkForced((roomOrigin.getX() + dx) >> 4, roomOrigin.getZ() >> 4, false);
            }
            helper.assertTrue(found, "a librarian 18 blocks past the room centre, in the staging room, is found");
            helper.succeed();
        });
    }

    /**
     * PD-78 and PD-97 were the same authoring mistake in two rooms, each found
     * by a player whose door would not build. Every multi-story room in the
     * manifest, stamped from its baked template at every rotation, has a
     * return path, so a third one fails here instead.
     */
    @GameTest(maxTicks = 100)
    public void everyMultiStoryRoomHasAReturnPath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        java.util.List<String> failures = new java.util.ArrayList<>();
        int checked = 0;
        int row = 0;
        for (RoomManifest.Entry entry : RoomManifest.current().rooms()) {
            if (entry.meta.spanY <= 1) {
                continue;
            }
            checked++;
            row++;
            for (int q = 0; q < 4; q++) {
                BlockPos origin = new BlockPos(6144 + q * 2 * RoomGeometry.CELL, 120,
                        6144 + row * 2 * RoomGeometry.CELL);
                level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
                try {
                    TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                            net.minecraft.resources.Identifier.parse(entry.meta.template), q, 1L);
                    if (!ReturnPathValidator.validate(level, origin, entry.meta.spanY)) {
                        failures.add(entry.name + " at " + q + " quarter turns");
                    }
                } finally {
                    level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
                }
            }
        }
        helper.assertTrue(checked >= 3, "the manifest has its multi-story rooms (found " + checked + ")");
        helper.assertTrue(failures.isEmpty(), "multi-story rooms with no return path: " + failures);
        helper.succeed();
    }

    /**
     * PD-93: a themed plan whose anomaly roll swapped in an anomaly room
     * stamps. The room lives in the anomaly manifest, and the stamper used to
     * look every cell up in the themed one, so every commit of such a door
     * threw "manifest has no room named pocketdungeons:anomaly_...".
     */
    @GameTest(maxTicks = 200)
    public void anomalyRoomStampsFromTheAnomalyManifest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DungeonPlan plan = null;
        for (long seed = 1; seed < 4000 && plan == null; seed++) {
            LayoutPlanner.Outcome outcome = LayoutPlanner.plan(seed, RoomManifest.current(),
                    LayoutPlanner.DEFAULT_ATTEMPT_BUDGET,
                    LayoutPlanner.DEFAULT_MIN_PATH, LayoutPlanner.DEFAULT_MAX_PATH,
                    LayoutPlanner.DEFAULT_BRANCH_PROBABILITY, LayoutPlanner.DEFAULT_LOOP_PROBABILITY,
                    LayoutPlanner.DEFAULT_MAX_GRID_SPAN, "frostworks", null);
            if (outcome.plan() != null && outcome.plan().anomalyCell() != null) {
                plan = outcome.plan();
            }
        }
        helper.assertTrue(plan != null, "some frostworks seed rolls an anomaly room");

        String anomalyRoom = plan.rooms().get(plan.anomalyCell()).name();
        helper.assertTrue(RoomManifest.current().byName(anomalyRoom) == null,
                anomalyRoom + " is absent from the themed manifest, so this test covers PD-93");
        helper.assertTrue(LayoutStamper.entryAt(RoomManifest.current(), plan, plan.anomalyCell()) != null,
                anomalyRoom + " resolves for the anomaly cell");

        int minX = plan.cells().stream().mapToInt(PlanCell::x).min().orElse(0);
        int minZ = plan.cells().stream().mapToInt(PlanCell::z).min().orElse(0);
        BlockPos origin = new BlockPos(8192 - minX * RoomGeometry.CELL, 120, 8192 - minZ * RoomGeometry.CELL);
        java.util.List<net.minecraft.world.level.ChunkPos> chunks = PlanGeometry.of(origin, plan.cells()).chunks();
        Instances.forceLoad(level, chunks, true);
        try {
            LayoutStamper.stamp(level, origin, plan, 6, java.util.Set.of());
        } finally {
            Instances.forceLoad(level, chunks, false);
        }
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

    /** Air or ladder: a block a player can pass through in a shaft. */
    /**
     * Playtest 2026-10-02-1: every staging room carries the run storage ender
     * chest, set into the wall right of the selector doors and facing into the
     * room, at every rotation. Shell, so nobody can break it, and clear of every
     * furniture position.
     */
    @GameTest(maxTicks = 100)
    public void stagingRoomCarriesTheRunStorageChest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DoorMask.Direction[] walls = DoorMask.Direction.values();
        java.util.List<BlockPos> origins = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < walls.length; i++) {
                BlockPos origin = new BlockPos(4096 + i * 2 * RoomGeometry.CELL, 120, 4096 + 9 * RoomGeometry.CELL);
                origins.add(origin);
                level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
                Instances.stampStagingRoom(level, origin, walls[i]);
                BlockPos chest = RoomTemplateGenerator.runStoragePos(origin, walls[i]);
                BlockState state = level.getBlockState(chest);
                helper.assertTrue(state.is(Blocks.ENDER_CHEST), walls[i] + ": the ender chest is in the wall, found " + state);
                helper.assertTrue(RunStorage.matchesStation(state), walls[i] + ": the chest opens run storage");
                Direction facing = state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
                helper.assertTrue(level.getBlockState(chest.relative(facing)).isAir(),
                        walls[i] + ": the chest faces into the room, not into the wall");
                helper.assertTrue(RoomProtection.isShell(chest, origin), walls[i] + ": the chest is shell");
                helper.assertFalse(RoomProtection.isFurniture(chest, origin, walls[i]),
                        walls[i] + ": the chest is clear of the furniture");
            }
            helper.succeed();
        } finally {
            for (BlockPos origin : origins) {
                level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            }
        }
    }

    /**
     * PD-109 follow-up: the traps do not only survive the stamp, they fire. The
     * room is stamped the way the playtest's infestation floor stamped it (the
     * theme_deepslate processors), something steps on the middle of the first
     * wire, and the dispenser that wire feeds must spend an arrow.
     */
    @GameTest(maxTicks = 240)
    public void tripwireHallTrapsFireUnderTheTheme(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(8192, 120, 12288 + 4 * RoomGeometry.CELL);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        BlockPos dispenser = origin.offset(4, 2, 0);
        // The test world persists between runs: arrows and drops left by an earlier
        // run lie on the wire and would fire the fresh dispenser during the stamp.
        // The chunk has to be loaded before its old entities can be found, so the
        // sweep and the stamp wait for it.
        helper.runAfterDelay(20, () -> {
            clearRoomEntities(level, origin);
            TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                    net.minecraft.resources.Identifier.parse("pocketdungeons:rooms/tripwire_hall"), 0, 1L,
                    net.minecraft.resources.Identifier.parse("pocketdungeons:theme_deepslate"));
        });
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(level.getBlockState(origin.offset(4, 1, 7)).is(Blocks.TRIPWIRE),
                    "the first wire crosses the doorway lane");
            helper.assertTrue(level.getBlockEntity(dispenser) instanceof Container c && c.getItem(0).getCount() == 3,
                    "the dispenser starts with three arrows, found "
                            + level.getBlockState(dispenser) + " holding "
                            + (level.getBlockEntity(dispenser) instanceof Container c2 ? c2.getItem(0) : "no container"));
            net.minecraft.world.entity.decoration.ArmorStand stand = new net.minecraft.world.entity.decoration.ArmorStand(
                    level, origin.getX() + 4.5, origin.getY() + 1, origin.getZ() + 7.5);
            level.addFreshEntity(stand);
        });
        helper.runAfterDelay(100, () -> {
            try {
                int left = level.getBlockEntity(dispenser) instanceof Container c ? c.getItem(0).getCount() : -1;
                helper.assertTrue(left >= 0 && left < 3, "stepping on the wire fires its dispenser, arrows left " + left
                        + ", hook " + level.getBlockState(origin.offset(4, 1, 1)));
                helper.succeed();
            } finally {
                clearRoomEntities(level, origin);
                level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            }
        });
    }

    /**
     * Playtest 2026-10-03: every big title goes through {@link StaggeredTitle}.
     * A sequence for a player who is not online ends at once instead of
     * throwing or lingering, so a logout mid-reveal (or a floor commit racing a
     * disconnect) leaves nothing running.
     */
    @GameTest(maxTicks = 100)
    public void staggeredTitleForAnOfflinePlayerDrains(GameTestHelper helper) {
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        StaggeredTitle.show(helper.getLevel().getServer(), player.getUUID(),
                net.minecraft.network.chat.Component.literal("HOME"),
                java.util.List.of("one", "two", "three"), net.minecraft.ChatFormatting.GRAY);
        helper.runAfterDelay(4 * StaggeredTitle.BEAT_TICKS, () -> {
            helper.assertFalse(StaggeredTitle.isRunning(player.getUUID()), "the sequence drained");
            helper.succeed();
        });
    }

    /**
     * PD-165: a milestone title is queued behind the floor clear's own title,
     * not shown at once (a new sequence replaces a running one, which is how the
     * first-clear title was overwritten unseen). It waits, and when due it leaves
     * the queue, shows and calls its fanfare.
     */
    @GameTest(maxTicks = 160)
    public void aMilestoneTitleWaitsBehindTheFloorClear(GameTestHelper helper) {
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        boolean[] fanfare = {false};
        StaggeredTitle.showMilestone(helper.getLevel().getServer(), player.getUUID(),
                net.minecraft.network.chat.Component.literal("Copper Works cleared"),
                java.util.List.of("Act 1: 1 of 5 dungeons"), net.minecraft.ChatFormatting.GRAY,
                p -> fanfare[0] = true);
        helper.assertValueEqual(StaggeredTitle.pendingFor(player.getUUID()), 1, "queued at once");
        helper.assertFalse(StaggeredTitle.isRunning(player.getUUID()), "and not shown at once");
        helper.runAfterDelay(StaggeredTitle.MILESTONE_DELAY_TICKS / 2, () -> {
            helper.assertValueEqual(StaggeredTitle.pendingFor(player.getUUID()), 1, "still waiting halfway");
            helper.assertFalse(fanfare[0], "no fanfare before it is due");
        });
        helper.runAfterDelay(StaggeredTitle.MILESTONE_DELAY_TICKS + 20, () -> {
            helper.assertValueEqual(StaggeredTitle.pendingFor(player.getUUID()), 0, "out of the queue when due");
            helper.assertTrue(fanfare[0], "the fanfare played when it showed");
            helper.succeed();
        });
    }

    /**
     * Playtest 2026-10-03 (A9): the room scan reads what a real capture writes.
     * A grindstone, a crafting table and a chest of planks are captured the way
     * {@link RoomStore#capture} captures a room, and the summary must name them.
     * The blob keys the scan relies on (palette, blocks, state, nbt, Items) are
     * exactly what this exercises, so a format change fails here, not in play.
     */
    @GameTest
    public void roomScanReadsStationsAndChestsFromARealCapture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 1, 0));
        level.setBlock(base, Blocks.GRINDSTONE.defaultBlockState(), 3);
        level.setBlock(base.east(), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        level.setBlock(base.east(2), Blocks.CHEST.defaultBlockState(), 3);
        net.minecraft.world.Container chest = (net.minecraft.world.Container) level.getBlockEntity(base.east(2));
        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 12));
        chest.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 5));

        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template =
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
        template.fillFromWorld(level, base, new net.minecraft.core.Vec3i(3, 1, 1), true, java.util.List.of());
        net.minecraft.nbt.CompoundTag blob = template.save(new net.minecraft.nbt.CompoundTag());

        RoomScan.Summary summary = RoomScan.summarize(blob, RoomScan.stationBlockIds());
        helper.assertValueEqual(summary.stations().get("grindstone"), 1, "the grindstone is a station");
        helper.assertValueEqual(summary.stations().get("crafting_table"), 1, "the crafting table is a station");
        helper.assertValueEqual(summary.containers(), 1, "one chest stands in the room");
        helper.assertValueEqual(summary.items().get("oak_planks"), 12, "the planks in it are counted");
        helper.assertValueEqual(summary.items().get("iron_ingot"), 5, "so is the iron");
        helper.assertTrue(RoomScan.tip(summary) == null, "a room with a crafting table needs no tip");
        helper.assertTrue(RoomScan.toJson(summary).getAsJsonObject("stations").has("grindstone"), "and the JSON names it");
        helper.succeed();
    }

    /** Discards everything but players around a cell: the stand, its drops and the arrows the traps fired. */
    private static void clearRoomEntities(ServerLevel level, BlockPos origin) {
        level.getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(origin).inflate(RoomGeometry.CELL),
                e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(e -> e.discard());
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(net.minecraft.world.level.block.Blocks.LADDER);
    }
}
