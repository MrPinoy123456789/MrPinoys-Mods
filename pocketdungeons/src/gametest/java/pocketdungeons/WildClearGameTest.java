package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wild lodestones phase 3: the clear, the rewards, the clear Exit block, the cooldown, the reset and the sweep. Like
 * {@link WildRunGameTest} these build the dungeon in the test level, since the gametest server has no dungeon dimension.
 */
public final class WildClearGameTest {

    private static int slotOf(int test) {
        return 9981 + test;
    }

    private static BlockPos originOf(int test) {
        return new BlockPos(14400 + 1024 * test, 120, 14400);
    }

    private static WildState.Lodestone lodestone(ServerLevel level, String offering, String id, BlockPos at) {
        return new WildState.Lodestone(id, WildLodestones.dimensionKey(level), at.getX(), at.getY(), at.getZ(), offering,
                2, 1000L, 1000L + 7L * 86400L);
    }

    private static void cleanUp(ServerLevel level, InstanceRecord record) {
        if (record == null) {
            return;
        }
        InstanceRegistry.bySlot.remove(record.slot);
        InstanceRegistry.usedSlots.remove(record.slot);
        for (java.util.UUID member : new ArrayList<>(record.members.keySet())) {
            InstanceRegistry.byMember.remove(member);
        }
        Set<BlockPos> cells = new LinkedHashSet<>(record.liveCells());
        for (BlockPos cell : cells) {
            InstanceTeardown.clearCellSync(level, cell, List.of());
        }
        InstanceTeardown.releaseCells(level, cells, List.of());
        WildLodestones.forgetExits(record.wildId);
    }

    private static InstanceRecord stamp(GameTestHelper helper, WildState.Lodestone lode, int test) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.Offering offering = WildDungeons.byId(lode.dungeon);
        WildRun.PlannedFloor planned = WildRun.planFor(level, lode, offering);
        helper.assertTrue(planned != null, "plans");
        InstanceRegistry.usedSlots.add(slotOf(test));
        InstanceRecord record = WildRun.stampAt(server, level, lode, planned, slotOf(test), originOf(test));
        helper.assertTrue(record != null, "stamps");
        return record;
    }

    /** A player who is teleported into a wild dungeon (not through the entrance) still counts as inside it. */
    @SuppressWarnings("removal")
    @GameTest(maxTicks = 100)
    public void aTeleportedPlayerIsAdoptedAsAMember(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        WildState.Lodestone lode = lodestone(level, "pocketdungeons:boiler_hall", "w-adopt", new BlockPos(5, 70, 5));
        WildState state = new WildState();
        state.add(lode);
        WildStore.setForTesting(state);
        WildMode.setActiveForTesting(true);
        WildReset.setDungeonForTesting(lode.id, level);
        InstanceRecord record = null;
        try {
            record = stamp(helper, lode, 40);
            lode.markInProgress();
            ServerPlayer inside = helper.makeMockServerPlayerInLevel();
            ServerPlayer outside = helper.makeMockServerPlayerInLevel();
            BlockPos entrance = record.layout.entrance();
            inside.teleportTo(level, entrance.getX() + 0.5, entrance.getY(), entrance.getZ() + 0.5, Set.of(), 0F, 0F,
                    false);
            outside.teleportTo(level, entrance.getX() + 5000.5, entrance.getY(), entrance.getZ() + 0.5, Set.of(), 0F, 0F,
                    false);
            helper.assertTrue(record.members.isEmpty(), "nobody is a member yet");

            WildRun.adoptStrangers(server, record, state, lode);

            helper.assertTrue(record.members.containsKey(inside.getUUID()), "the teleported player is a member");
            helper.assertTrue(InstanceRegistry.byMember.get(inside.getUUID()) == record, "and is registered inside");
            helper.assertTrue(!record.members.containsKey(outside.getUUID()), "a player far from the dungeon is not");
            helper.assertValueEqual(state.lastLodestoneOf(inside.getUUID()), lode.id, "their way out is the lodestone");
            ReturnPoint back = record.members.get(inside.getUUID());
            helper.assertTrue(back.pos().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(entrance)) > 100.0,
                    "their return point is not a spot inside the dungeon");
            WildRun.adoptStrangers(server, record, state, lode);
            helper.assertValueEqual(record.members.size(), 1, "a second pass adds nobody and drops nobody");
        } finally {
            WildReset.setDungeonForTesting(lode.id, null);
            WildStore.setForTesting(null);
            WildMode.setActiveForTesting(null);
            cleanUp(level, record);
        }
        helper.succeed();
    }

    /** A clear pays the floor chests, places the clear Exit, starts the cooldown and reaches the currency sink once. */
    @SuppressWarnings("removal")
    @GameTest(maxTicks = 600)
    public void aClearPaysPlacesTheExitAndStartsTheCooldown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        WildState.Lodestone lode = lodestone(level, "pocketdungeons:boiler_hall", "w-clear", new BlockPos(5, 70, 5));
        WildState state = new WildState();
        state.add(lode);
        WildStore.setForTesting(state);
        WildMode.setActiveForTesting(true);
        int[] paid = {0};
        int[] playersPaid = {0};
        WildRewards.setSink(payout -> {
            paid[0]++;
            playersPaid[0] = payout.players().size();
        });
        InstanceRecord record = null;
        try {
            record = stamp(helper, lode, 20);
            lode.markInProgress();
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, player.position(), 0.0f, 0.0f));
            InstanceRegistry.byMember.put(player.getUUID(), record);

            WildClear.clear(server, level, record, lode);

            BlockPos terminal = record.layout.terminal();
            DoorMask.Direction dir = CellGeometry.terminalEntranceDirection(record.layout.geometry(), terminal);
            BlockPos exit = TrialContent.exitSpot(terminal, dir);
            helper.assertTrue(level.getBlockState(exit).is(Blocks.BEDROCK), "the clear Exit marker stands");
            helper.assertTrue(WildLodestones.hasExit(lode.id, true), "the clear Exit is registered");
            helper.assertTrue(lode.phase == WildState.Phase.CLEARED, "the lodestone is cleared");
            helper.assertTrue(lode.resetAt > lode.clearedAt, "the cooldown runs");
            helper.assertTrue(record.phase == RunSession.Phase.ACTIVE, "the run is not advanced");
            helper.assertTrue(record.floor.completed.isEmpty(), "the clear adds nothing to floor.completed");
            helper.assertTrue(paid[0] == 1 && playersPaid[0] == 1, "the sink was called once with the player inside");
            boolean barrel = false;
            boolean copper = false;
            var copperBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                    net.minecraft.resources.Identifier.withDefaultNamespace("copper_chest"));
            // Wild loot is war supplies (owner, 2026-11-02): the barrel holds nothing else.
            java.util.Set<net.minecraft.world.item.Item> supplies = java.util.Set.of(Items.GOLD_INGOT, Items.GOLDEN_APPLE,
                    Items.IRON_INGOT, Items.EMERALD, Items.NETHER_WART, Items.TRIAL_KEY);
            int barrelStacks = 0;
            for (BlockPos pos : BlockPos.betweenClosed(terminal, terminal.offset(15, 4, 15))) {
                var block = level.getBlockState(pos).getBlock();
                barrel |= block == Blocks.BARREL;
                copper |= block == copperBlock;
                if (block == Blocks.BARREL && level.getBlockEntity(pos) instanceof net.minecraft.world.Container box) {
                    for (int slot = 0; slot < box.getContainerSize(); slot++) {
                        net.minecraft.world.item.ItemStack stack = box.getItem(slot);
                        if (stack.isEmpty()) {
                            continue;
                        }
                        barrelStacks++;
                        helper.assertTrue(supplies.contains(stack.getItem()), "the wild barrel holds " + stack + ", not a war supply");
                    }
                }
            }
            helper.assertTrue(barrelStacks > 0, "the wild barrel pays something");
            // Room chests and vaults keep their table until opened: every one names a wild table.
            for (BlockPos cell : record.liveCells()) {
                for (BlockPos pos : BlockPos.betweenClosed(cell.offset(0, -16, 0), cell.offset(15, 15, 15))) {
                    if (level.getBlockEntity(pos) instanceof net.minecraft.world.RandomizableContainer box
                            && box.getLootTable() != null) {
                        String table = box.getLootTable().identifier().getPath();
                        helper.assertTrue(table.endsWith(WildLoot.SUFFIX), "a wild room chest rolls " + table);
                    }
                }
            }
            helper.assertTrue(barrel, "the reward barrel stands in the terminal cell");
            helper.assertTrue(copper, "the copper chest stands in the terminal cell");
        } finally {
            WildRewards.setSink(null);
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            cleanUp(level, record);
        }
        helper.succeed();
    }

    /** The watcher does not clear a floor whose spawners still stand. */
    @SuppressWarnings("removal")
    @GameTest(maxTicks = 600)
    public void theWatcherWaitsForTheGate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        WildState.Lodestone lode = lodestone(level, "pocketdungeons:boiler_hall", "w-gate", new BlockPos(5, 70, 5));
        WildState state = new WildState();
        state.add(lode);
        WildStore.setForTesting(state);
        WildMode.setActiveForTesting(true);
        WildReset.setDungeonForTesting(lode.id, level);
        InstanceRecord record = null;
        try {
            record = stamp(helper, lode, 21);
            lode.markInProgress();
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, player.position(), 0.0f, 0.0f));
            InstanceRegistry.byMember.put(player.getUUID(), record);
            helper.assertTrue(RunLifecycle.padRefusal(server, record, level) != null, "the floor still has work");
            WildClear.tick(server, record, lode, 20L);
            helper.assertTrue(lode.phase == WildState.Phase.IN_PROGRESS, "the watcher did not clear it");
            helper.assertTrue(!WildLodestones.hasExit(lode.id, true), "no clear Exit yet");
        } finally {
            WildReset.setDungeonForTesting(lode.id, null);
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            cleanUp(level, record);
        }
        helper.succeed();
    }

    /** A reset sweeps ground items into chests beside the lodestone, tears down and reopens the lodestone. */
    @SuppressWarnings("removal")
    @GameTest(maxTicks = 600)
    public void aResetSweepsItemsAndReopensTheLodestone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        BlockPos site = helper.absolutePos(new BlockPos(2, 1, 2));
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                level.setBlock(site.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        WildState.Lodestone lode = lodestone(level, "pocketdungeons:boiler_hall", "w-reset", site);
        WildState state = new WildState();
        state.add(lode);
        WildStore.setForTesting(state);
        WildMode.setActiveForTesting(true);
        WildReset.setDungeonForTesting(lode.id, level);
        WildClock.setSourceForTesting(() -> 500L);
        InstanceRecord record = null;
        try {
            record = stamp(helper, lode, 22);
            lode.markCleared(1000L, 60L);
            BlockPos at = record.layout.entrance();
            InstanceRecord live = record;
            helper.runAfterDelay(40L, () -> {
                level.getChunk(at.getX() >> 4, at.getZ() >> 4);
                ItemEntity dropped = new ItemEntity(level, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                        new ItemStack(Items.DIAMOND, 5));
                boolean added = level.addFreshEntity(dropped);
                helper.runAfterDelay(5L, () -> {
                    try {
                        helper.assertTrue(added && dropped.isAlive(), "the diamonds were dropped: added " + added
                                + " alive " + dropped.isAlive() + " chunkLoaded "
                                + level.hasChunkAt(at));
                        WildReset.step(server, lode, 500L);
                        helper.assertTrue(lode.phase == WildState.Phase.CLEARED, "not due yet: still cleared");
                        WildReset.reset(server, lode, live);
                        helper.assertTrue(lode.phase == WildState.Phase.READY, "the lodestone is ready again");
                        helper.assertTrue(WildRun.liveRecord(lode.id) == null, "the record is gone");
                        helper.assertTrue(!WildLodestones.hasExit(lode.id, false)
                                && !WildLodestones.hasExit(lode.id, true), "no Exit blocks remain registered");
                        helper.assertTrue(!lode.lostAndFound.isEmpty(), "a lost and found chest was recorded: " + WildSweep.lastReport);
                        int diamonds = 0;
                        for (int[] pos : lode.lostAndFound) {
                            if (level.getBlockEntity(new BlockPos(pos[0], pos[1], pos[2])) instanceof ChestBlockEntity chest) {
                                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                                    ItemStack stack = chest.getItem(slot);
                                    if (stack.is(Items.DIAMOND)) {
                                        diamonds += stack.getCount();
                                    }
                                }
                            }
                        }
                        helper.assertTrue(diamonds == 5, "the dropped diamonds are in the chest: " + diamonds);
                        helper.succeed();
                    } finally {
                        WildClock.setSourceForTesting(null);
                        WildReset.setDungeonForTesting(lode.id, null);
                        WildMode.setActiveForTesting(null);
                        WildStore.setForTesting(null);
                        cleanUp(level, live);
                    }
                });
            });
            return;
        } catch (RuntimeException e) {
            WildClock.setSourceForTesting(null);
            WildReset.setDungeonForTesting(lode.id, null);
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            cleanUp(level, record);
            throw e;
        }
    }

    /** An in progress lodestone with nothing behind it (a restart) goes back to ready. */
    @SuppressWarnings("removal")
    @GameTest
    public void aLodestoneLeftInProgressByARestartReopens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildState.Lodestone lode = lodestone(level, "pocketdungeons:boiler_hall", "w-orphan", new BlockPos(5, 70, 5));
        WildState state = new WildState();
        state.add(lode);
        WildStore.setForTesting(state);
        try {
            lode.markInProgress();
            WildReset.step(server, lode, 5000L);
            helper.assertTrue(lode.phase == WildState.Phase.READY, "reopened");
        } finally {
            WildStore.setForTesting(null);
        }
        helper.succeed();
    }

    /** Bonus rewards roll by chance and merge swept stacks. */
    @GameTest
    public void bonusRewardsRollAndSweptStacksMerge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WildDungeons.Offering always = new WildDungeons.Offering("t:always", "Always", "d", "n", "", 10, 1, 0, 1,
                WildDungeons.DUNGEON, "", List.of(new WildDungeons.Bonus("minecraft:diamond", 2, 1.0),
                        new WildDungeons.Bonus("minecraft:not_an_item", 1, 1.0)));
        List<ItemStack> rolled = WildRewards.rollBonus(always, level.getRandom());
        helper.assertTrue(rolled.size() == 1 && rolled.get(0).is(Items.DIAMOND) && rolled.get(0).getCount() == 2,
                "a sure bonus rolls, an unknown item is skipped: " + rolled);
        WildDungeons.Offering never = new WildDungeons.Offering("t:never", "Never", "d", "n", "", 10, 1, 0, 1,
                WildDungeons.DUNGEON, "", List.of(new WildDungeons.Bonus("minecraft:diamond", 2, 0.0)));
        helper.assertTrue(WildRewards.rollBonus(never, level.getRandom()).isEmpty(), "a zero chance never rolls");

        List<ItemStack> merged = WildSweep.merge(List.of(new ItemStack(Items.COBBLESTONE, 40),
                new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.DIAMOND, 1), ItemStack.EMPTY));
        int cobble = 0;
        for (ItemStack stack : merged) {
            if (stack.is(Items.COBBLESTONE)) {
                cobble += stack.getCount();
                helper.assertTrue(stack.getCount() <= 64, "no stack over its size");
            }
        }
        helper.assertTrue(cobble == 80 && merged.size() == 3, "80 cobblestone in two stacks plus the diamond: " + merged);
        helper.succeed();
    }

    /** A collapse closes the dungeon, turns the entrance to rubble and retires the lodestone. */
    @SuppressWarnings("removal")
    @GameTest(maxTicks = 600)
    public void aCollapseClosesTheDungeonAndRetiresTheLodestone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        BlockPos site = helper.absolutePos(new BlockPos(2, 1, 2));
        WildState.Lodestone lode = lodestone(level, "pocketdungeons:boiler_hall", "w-collapse", site);
        WildState state = new WildState();
        state.add(lode);
        WildStore.setForTesting(state);
        WildMode.setActiveForTesting(true);
        WildReset.setDungeonForTesting(lode.id, level);
        InstanceRecord record = null;
        try {
            WildLodestones.stamp(level, site);
            helper.assertTrue(level.getBlockState(site).is(Blocks.BEDROCK), "the entrance stands before the collapse");
            record = stamp(helper, lode, 23);
            lode.markInProgress();
            WildCollapse.collapse(server, lode);
            helper.assertTrue(state.get(lode.id) == null, "the lodestone is gone from the live list");
            helper.assertTrue(state.retired().size() == 1 && state.retired().get(0).id().equals(lode.id),
                    "it is on the retired list");
            helper.assertTrue(WildRun.liveRecord(lode.id) == null, "the dungeon is closed");
            helper.assertTrue(!level.getBlockState(site).is(Blocks.BEDROCK), "the entrance marker is gone");
        } finally {
            WildReset.setDungeonForTesting(lode.id, null);
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            cleanUp(level, record);
        }
        helper.succeed();
    }

    /** The top up places a ruin, records the lodestone with a lifespan and a level by distance. */
    @SuppressWarnings("removal")
    @GameTest
    public void theTopUpPlacesARuinAndRecordsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        WildState state = new WildState();
        WildStore.setForTesting(state);
        try {
            BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
            BlockPos spawn = at.offset(-1000, 0, 0);
            WildState.Lodestone placed = WildTopUp.place(level, state, at.getX(), at.getZ(), spawn);
            helper.assertTrue(placed != null && state.size() == 1, "one lodestone was recorded");
            helper.assertTrue(placed.level >= 1 && placed.expiresAt > placed.createdAt, "level and lifespan are set");
            helper.assertTrue(state.atEntrance(placed.dimension, placed.x, placed.y, placed.z) == placed,
                    "the entrance is found by its position");
            helper.assertTrue(level.getBlockState(new BlockPos(placed.x, placed.y, placed.z)).is(Blocks.BEDROCK),
                    "the entrance jigsaw stands");
        } finally {
            WildStore.setForTesting(null);
        }
        helper.succeed();
    }
}
