package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The relic floor (campaign and endgame T7, "one heavy thing"): a heavy core named Relic on a plinth in a branch
 * cell. It is greed and never the gate: carrying it to the pad pays extra chests, the pad opens without it and the
 * spawner gate is the floor's own. A rescue drops it into the holder's pot even from the hotbar; Dungeon Storage,
 * containers and block placement refuse it; if it leaves the floor any other way it returns to its plinth.
 *
 * <p>The record is built by hand over a staging cell, an entrance, a branch cell and a terminal, the way
 * {@link PackPotGameTest} and {@link GatherFloorGameTest} do.
 */
public final class RelicFloorGameTest {

    private static final int SLOT = 9150;
    private static final PlanCell STAGING = new PlanCell(0, 0);
    private static final PlanCell ENTRANCE = new PlanCell(1, 0);
    private static final PlanCell BRANCH = new PlanCell(1, 1);
    private static final PlanCell TERMINAL = new PlanCell(2, 0);

    /** A built floor: the record, its geometry, and the plan the relic was armed against. */
    private record Floor(InstanceRecord record, PlanGeometry geometry, DungeonPlan plan) {}

    private static Floor floor(GameTestHelper helper, UUID owner, List<PlanCell> planCells, List<PlanCell> path) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        List<PlanCell> all = new java.util.ArrayList<>(planCells);
        all.add(STAGING);
        PlanGeometry geometry = PlanGeometry.of(origin, all);
        for (BlockPos cellOrigin : geometry.cellOrigins()) {
            level.getChunk(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4);
            level.getChunk((cellOrigin.getX() + RoomGeometry.CELL - 1) >> 4, cellOrigin.getZ() >> 4);
            level.getChunk(cellOrigin.getX() >> 4, (cellOrigin.getZ() + RoomGeometry.CELL - 1) >> 4);
            level.getChunk((cellOrigin.getX() + RoomGeometry.CELL - 1) >> 4,
                    (cellOrigin.getZ() + RoomGeometry.CELL - 1) >> 4);
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cellOrigin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 1; y <= 6; y++) {
                        level.setBlock(cellOrigin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
        InstanceLayout layout = new InstanceLayout(origin, geometry, geometry.cellCentre(ENTRANCE), 0.0f,
                geometry.cellCentre(TERMINAL), geometry.bounds(), 1L, planCells.size() - 1, planCells.size(), 1,
                true, Set.of(), 3, geometry.cellOrigin(TERMINAL), 0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(SLOT, origin, level.getServer().getTickCount(), layout,
                Set.of(), owner, false);
        record.stagingCellOrigin = geometry.cellOrigin(STAGING);
        record.interval.dungeonId = "pocketdungeons:mineshaft";
        record.interval.nodeId = "adit";
        record.phase = RunSession.Phase.ACTIVE;
        record.members.put(owner, new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        DungeonPlan plan = new DungeonPlan(1L, Set.copyOf(planCells), Map.of(), Map.of(), Map.of(), Set.of(),
                ENTRANCE, TERMINAL, path, null);
        return new Floor(record, geometry, plan);
    }

    private static Floor branchFloor(GameTestHelper helper, UUID owner) {
        return floor(helper, owner, List.of(ENTRANCE, BRANCH, TERMINAL), List.of(ENTRANCE, TERMINAL));
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().clearContent();
        return player;
    }

    /** Arms the relic on the floor and registers the record the way a live run is: slot, member and the test seam. */
    private static RelicObjective arm(GameTestHelper helper, Floor floor, ServerPlayer... players) {
        InstanceRegistry.bySlot.put(SLOT, floor.record());
        InstanceRegistry.usedSlots.add(SLOT);
        for (ServerPlayer player : players) {
            InstanceRegistry.byMember.put(player.getUUID(), floor.record());
            PackPots.registerPlayerForTesting(player);
            floor.record().members.putIfAbsent(player.getUUID(),
                    new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        }
        FloorObjectives.registerForTesting(floor.record(), FloorObjective.RELIC);
        FloorObjectives.arm(helper.getLevel(), floor.record(), floor.plan(), null);
        helper.assertTrue(floor.record().floor.objective instanceof RelicObjective, "a relic floor arms a relic state");
        return (RelicObjective) floor.record().floor.objective;
    }

    private static void cleanup(MinecraftServer server, Floor floor, ServerPlayer... players) {
        FloorObjectives.forgetForTesting(floor.record());
        InstanceRegistry.bySlot.remove(SLOT);
        InstanceRegistry.usedSlots.remove(SLOT);
        DungeonWorldRules.everywhere = false;
        PackPots.clearForTesting(server);
        DungeonLog log = DungeonLog.forServer(server);
        for (ServerPlayer player : players) {
            InstanceRegistry.byMember.remove(player.getUUID());
            player.closeContainer();
            log.setStorage(player.getUUID(), List.of());
            InventorySwap.Probe.clearKept(player);
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, floor.geometry().bounds(),
                    e -> RelicObjective.isRelic(e.getItem()))) {
                entity.discard();
            }
        }
    }

    private static List<ItemEntity> relicsOnGround(ServerLevel level, Floor floor, String id) {
        return level.getEntitiesOfClass(ItemEntity.class, floor.geometry().bounds(),
                e -> id.equals(RelicObjective.idOf(e.getItem())));
    }

    private static int relicsHeld(ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (RelicObjective.isRelic(player.getInventory().getItem(i))) {
                total++;
            }
        }
        return total;
    }

    @GameTest(maxTicks = 40)
    public void theRelicWaitsOnAPlinthInABranchCellAndNeverGatesThePad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer member = player(helper);
        Floor floor = branchFloor(helper, member.getUUID());
        try {
            RelicObjective state = arm(helper, floor, member);
            helper.assertTrue(!state.fallback, "the plinth was placed");
            BlockPos plinth = state.plinth;
            helper.assertTrue(BRANCH.equals(floor.geometry().cellAt(plinth)),
                    "the plinth stands in the branch cell, not the entrance or the terminal: " + plinth);
            helper.assertTrue(level.getBlockState(plinth).is(Blocks.CHISELED_POLISHED_BLACKSTONE), "a plinth block");
            helper.assertTrue(FloorObjectives.isFixture(plinth), "the plinth cannot be broken");
            List<ItemEntity> onGround = relicsOnGround(level, floor, state.floorId());
            helper.assertValueEqual(onGround.size(), 1, "one relic rests on the plinth");
            ItemStack item = onGround.get(0).getItem();
            helper.assertTrue(item.is(Items.HEAVY_CORE), "a vanilla heavy core");
            helper.assertValueEqual(item.getHoverName().getString(), "Relic", "named Relic");
            helper.assertValueEqual(item.get(DataComponents.MAX_STACK_SIZE), 1, "it does not stack");
            helper.assertTrue(onGround.get(0).getY() > plinth.getY() + 1.0 - 0.01
                    && onGround.get(0).getY() < plinth.getY() + 1.6, "and it sits on top of the plinth");

            helper.assertTrue(!FloorObjectives.gatesPad(floor.record()),
                    "the relic does not replace the spawner gate: greed, never the gate");
            helper.assertTrue(FloorObjectives.padRefusal(floor.record()) == null, "the pad has no relic refusal");
            helper.assertTrue(FloorObjectives.objectiveLine(floor.record()) == null,
                    "the bar keeps the spawner fraction");
            helper.assertValueEqual(FloorObjectives.titleLine(floor.record()), RelicObjective.TITLE, "the title speaks");
            helper.assertValueEqual(FloorObjectives.bonusRolls(floor.record(), 3, 100), 0,
                    "nobody carries it: the pad pays as it always did");
            helper.assertTrue(!state.delivered, "and nothing was delivered");
        } finally {
            cleanup(server, floor, member);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aFloorWithNoRoomForAPlinthIsAnOrdinarySpawnerFloor(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer member = player(helper);
        Floor floor = floor(helper, member.getUUID(), List.of(ENTRANCE, TERMINAL), List.of(ENTRANCE, TERMINAL));
        try {
            RelicObjective state = arm(helper, floor, member);
            helper.assertTrue(state.fallback, "only the entrance and the terminal: no relic");
            helper.assertTrue(FloorObjectives.isFallback(floor.record()), "the floor reads as a fallback");
            helper.assertTrue(!FloorObjectives.gatesPad(floor.record()), "and the spawner gate runs as ever");
            helper.assertTrue(FloorObjectives.titleLine(floor.record()) == null, "no title line for a fallback");
        } finally {
            cleanup(server, floor, member);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void carryingItToThePadPaysOnceAndTheFloorOpensWithoutIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer carrier = player(helper);
        ServerPlayer other = player(helper);
        Floor floor = branchFloor(helper, carrier.getUUID());
        try {
            RelicObjective state = arm(helper, floor, carrier, other);
            helper.assertValueEqual(FloorObjectives.bonusRolls(floor.record(), 3, 100), 0,
                    "without the relic the floor pays its base");
            helper.assertTrue(FloorObjectives.padRefusal(floor.record()) == null && !FloorObjectives.gatesPad(floor.record()),
                    "and the pad was open all along");
            carrier.getInventory().setItem(5, RelicObjective.item(state.floorId()));
            int pays = FloorObjectives.bonusRolls(floor.record(), 3, 100);
            helper.assertValueEqual(pays, FloorObjectiveRules.relicBonusRolls(3, 100, PocketDungeonsConfig.relicLootPercent()),
                    "carried to the pad it adds relicLootPercent to the Loot percent");
            helper.assertValueEqual(pays, 3, "at the defaults an unwagered floor of 3 chests pays 3 more");
            helper.assertValueEqual(relicsHeld(carrier), 0, "the relic was handed in");
            helper.assertTrue(state.delivered, "and it is delivered");
            helper.assertValueEqual(FloorObjectives.bonusRolls(floor.record(), 3, 100), 0, "it pays once");
            FloorObjectives.tick(server, floor.record());
            helper.assertValueEqual(relicsOnGround(helper.getLevel(), floor, state.floorId()).size(), 1,
                    "a delivered relic is never put back (only the one that rested there at the start)");
        } finally {
            cleanup(server, floor, carrier, other);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theHolderIsSlowedAndALostRelicReturnsToItsPlinth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer holder = player(helper);
        Floor floor = branchFloor(helper, holder.getUUID());
        try {
            RelicObjective state = arm(helper, floor, holder);
            relicsOnGround(level, floor, state.floorId()).forEach(ItemEntity::discard);
            holder.getInventory().setItem(2, RelicObjective.item(state.floorId()));
            FloorObjectives.tick(server, floor.record());
            helper.assertTrue(holder.hasEffect(MobEffects.SLOWNESS), "the holder has Slowness");
            helper.assertValueEqual(holder.getEffect(MobEffects.SLOWNESS).getAmplifier(), 0, "Slowness I");
            helper.assertValueEqual(relicsOnGround(level, floor, state.floorId()).size(), 0,
                    "held, it is not put back");

            holder.getInventory().setItem(2, ItemStack.EMPTY);
            holder.removeEffect(MobEffects.SLOWNESS);
            FloorObjectives.tick(server, floor.record());
            helper.assertValueEqual(relicsOnGround(level, floor, state.floorId()).size(), 1,
                    "gone from every pack, the ground and every pot: it returns to its plinth");
            helper.assertTrue(!holder.hasEffect(MobEffects.SLOWNESS), "and the slowness is gone with it");
            FloorObjectives.tick(server, floor.record());
            helper.assertValueEqual(relicsOnGround(level, floor, state.floorId()).size(), 1, "never twice");
        } finally {
            cleanup(server, floor, holder);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aRescueDropsItIntoThePotEvenFromTheHotbar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer holder = player(helper);
        Floor floor = branchFloor(helper, holder.getUUID());
        DungeonWorldRules.everywhere = true;
        try {
            RelicObjective state = arm(helper, floor, holder);
            relicsOnGround(level, floor, state.floorId()).forEach(ItemEntity::discard);
            BlockPos fell = floor.geometry().cellOrigin(ENTRANCE).offset(3, 1, 4);
            holder.setPos(Vec3.atBottomCenterOf(fell));
            holder.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            holder.getInventory().setItem(3, RelicObjective.item(state.floorId()));
            // A full pack: the relic has to take a slot from it, and what it displaces must not be lost.
            for (int slot = 9; slot <= 35; slot++) {
                holder.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 10 + slot));
            }
            Instances.rescue(holder, floor.record());

            helper.assertValueEqual(relicsHeld(holder), 0, "the relic left the hotbar");
            helper.assertTrue(holder.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "the rest of the hotbar stays");
            helper.assertTrue(holder.getInventory().getItem(3).is(Items.COBBLESTONE),
                    "the stack the relic displaced took its hand slot, so nothing was lost");
            helper.assertTrue(PackPots.holdsRelic(server, floor.record(), state.floorId()), "the pot holds the relic");
            int cobble = 0;
            for (PackPot pot : DungeonLog.forServer(server).packPotsOf(holder.getUUID())) {
                helper.assertValueEqual(pot.stacks().size(), 27, "the pot still has 27 slots (no format change)");
                for (ItemStack stack : pot.stacks()) {
                    if (stack.is(Items.COBBLESTONE)) {
                        cobble += stack.getCount();
                    }
                }
            }
            cobble += holder.getInventory().getItem(3).getCount();
            int expected = 0;
            for (int slot = 9; slot <= 35; slot++) {
                expected += 10 + slot;
            }
            helper.assertValueEqual(cobble, expected, "every cobblestone is in the pot or the hand slot");
            FloorObjectives.tick(server, floor.record());
            helper.assertValueEqual(relicsOnGround(level, floor, state.floorId()).size(), 0,
                    "a relic in a pot is on the floor: it is not put back");
        } finally {
            cleanup(server, floor, holder);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aRelicInAPotIsGoneWhenThePotSettlesAndWithTheRunAfterACrash(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer holder = player(helper);
        Floor floor = branchFloor(helper, holder.getUUID());
        DungeonWorldRules.everywhere = true;
        try {
            RelicObjective state = arm(helper, floor, holder);
            holder.setPos(Vec3.atBottomCenterOf(floor.geometry().cellOrigin(ENTRANCE).offset(3, 1, 4)));
            holder.getInventory().setItem(1, RelicObjective.item(state.floorId()));
            holder.getInventory().setItem(10, new ItemStack(Items.TORCH, 8));
            Instances.rescue(holder, floor.record());
            helper.assertTrue(PackPots.holdsRelic(server, floor.record(), state.floorId()), "in the pot");
            PackPots.settleRun(server, floor.record(), 100, false);
            helper.assertValueEqual(relicsHeld(holder), 0, "settling a pot never hands the relic to anyone");
            boolean storageHasIt = DungeonLog.forServer(server).storageOf(holder.getUUID()).stream()
                    .anyMatch(RelicObjective::isRelic);
            helper.assertTrue(!storageHasIt, "and never puts it in Dungeon Storage");
            helper.assertTrue(DungeonLog.forServer(server).packPotsOf(holder.getUUID()).isEmpty(), "the pot settled");

            // A second pot, then a restart: the run is gone, the pot returns in full minus the relic.
            // The first rescue detached the holder, so they rejoin the run first; the 8 torches the settle
            // paid out are cleared so the count below is the second pot's alone.
            holder.getInventory().clearContent();
            InstanceRegistry.byMember.put(holder.getUUID(), floor.record());
            floor.record().members.put(holder.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            holder.setPos(Vec3.atBottomCenterOf(floor.geometry().cellOrigin(ENTRANCE).offset(3, 1, 4)));
            holder.getInventory().setItem(1, RelicObjective.item(state.floorId()));
            holder.getInventory().setItem(10, new ItemStack(Items.TORCH, 8));
            Instances.rescue(holder, floor.record());
            PackPots.restartForTesting();
            PackPots.returnOrphans(server, holder.getUUID(), true);
            helper.assertValueEqual(relicsHeld(holder), 0, "an orphaned pot gives the relic to nobody");
            boolean storedRelic = DungeonLog.forServer(server).storageOf(holder.getUUID()).stream()
                    .anyMatch(RelicObjective::isRelic);
            helper.assertTrue(!storedRelic, "and never stores it");
            int torches = 0;
            for (int i = 0; i < holder.getInventory().getContainerSize(); i++) {
                if (holder.getInventory().getItem(i).is(Items.TORCH)) {
                    torches += holder.getInventory().getItem(i).getCount();
                }
            }
            for (ItemStack stack : DungeonLog.forServer(server).storageOf(holder.getUUID())) {
                if (stack.is(Items.TORCH)) {
                    torches += stack.getCount();
                }
            }
            helper.assertValueEqual(torches, 8, "the player's own things all came back");
        } finally {
            cleanup(server, floor, holder);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void storageContainersAndTheGroundRefuseIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper);
        Floor floor = branchFloor(helper, player.getUUID());
        try {
            RelicObjective state = arm(helper, floor, player);
            ItemStack relic = RelicObjective.item(state.floorId());

            List<ItemStack> overflow = RunStorage.deposit(server, player.getUUID(), List.of(relic.copy(),
                    new ItemStack(Items.TORCH, 3)));
            helper.assertValueEqual(overflow.size(), 1, "Dungeon Storage returns exactly the relic");
            helper.assertTrue(RelicObjective.isRelic(overflow.get(0)), "the refused stack is the relic");
            helper.assertTrue(DungeonLog.forServer(server).storageOf(player.getUUID()).stream()
                    .noneMatch(RelicObjective::isRelic), "the relic is not in the storage");
            helper.assertTrue(DungeonLog.forServer(server).storageOf(player.getUUID()).stream()
                    .anyMatch(s -> s.is(Items.TORCH)), "the torches were stored");
            helper.assertTrue(!new RunStorage.StorageContainer(DungeonLog.forServer(server), player.getUUID())
                    .canPlaceItem(0, relic), "no hopper or click places it either");
            helper.assertValueEqual(RelicObjective.WONT_FIT, "The relic will not fit.", "the refusal line");

            // A chest menu: the cursor cannot drop it in, shift click cannot send it in, a number key cannot swap it in.
            SimpleContainer chest = new SimpleContainer(27);
            ChestMenu menu = ChestMenu.threeRows(7, player.getInventory(), chest);
            helper.assertTrue(!menu.getSlot(0).mayPlace(relic), "no chest slot takes it");
            helper.assertTrue(menu.getSlot(27).mayPlace(relic), "the player's own slots still do");
            menu.setCarried(relic.copy());
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(chest.getItem(0).isEmpty(), "a click on the chest puts nothing in");
            helper.assertTrue(RelicObjective.isRelic(menu.getCarried()), "the relic stays on the cursor");
            menu.setCarried(ItemStack.EMPTY);
            player.getInventory().setItem(4, relic.copy());
            menu.clicked(27 + 27 + 4, 0, ContainerInput.QUICK_MOVE, player);
            helper.assertTrue(chest.isEmpty() && RelicObjective.isRelic(player.getInventory().getItem(4)),
                    "shift click leaves it in the pack");
            menu.clicked(0, 4, ContainerInput.SWAP, player);
            helper.assertTrue(chest.isEmpty() && RelicObjective.isRelic(player.getInventory().getItem(4)),
                    "a number key cannot swap it in");
            player.closeContainer();

            // A heavy core is a block: the relic is never set down.
            BlockPos ground = floor.geometry().cellOrigin(ENTRANCE).offset(5, 0, 5);
            player.setItemInHand(InteractionHand.MAIN_HAND, relic.copy());
            ItemStack inHand = player.getItemInHand(InteractionHand.MAIN_HAND);
            inHand.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false)));
            helper.assertTrue(level.getBlockState(ground.above()).isAir(), "the relic cannot be placed as a block");
        } finally {
            cleanup(server, floor, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aStaleOrForeignRelicIsSweptAndTheLiveOneStays(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer holder = player(helper);
        ServerPlayer stranger = player(helper);
        Floor floor = branchFloor(helper, holder.getUUID());
        try {
            RelicObjective state = arm(helper, floor, holder);
            holder.getInventory().setItem(1, RelicObjective.item(state.floorId()));
            holder.getInventory().setItem(2, RelicObjective.item("another-floor"));
            helper.assertValueEqual(RelicObjective.sweep(holder), 1, "the other floor's relic is removed");
            helper.assertValueEqual(relicsHeld(holder), 1, "the live one stays");
            helper.assertTrue(RelicObjective.idOf(holder.getInventory().getItem(1)).equals(state.floorId()),
                    "and it is the live one");

            stranger.getInventory().setItem(0, RelicObjective.item(state.floorId()));
            helper.assertValueEqual(RelicObjective.sweep(stranger), 1,
                    "a holder who is no longer on the floor (rescued, left) loses it; the watch puts it back");
            helper.assertValueEqual(relicsHeld(stranger), 0, "gone from the stranger");

            state.delivered = true;
            helper.assertValueEqual(RelicObjective.sweep(holder), 1, "once delivered, every copy is stale");
            helper.assertValueEqual(relicsHeld(holder), 0, "and none is left");
        } finally {
            cleanup(server, floor, holder, stranger);
        }
        helper.succeed();
    }
}
