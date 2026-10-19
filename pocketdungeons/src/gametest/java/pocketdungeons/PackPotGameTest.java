package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The pack and the pot (decision 2026-10-18, rules 1 to 8; build spec T4): a rescue moves exactly the 27 main
 * slots into a decorated pot where the player fell, and at the nearest door when that spot is no place for
 * one; a party member can take from it and nobody can put in; a floor end keeps half; a fail keeps half of
 * every pot and every pack; a restart returns a pot in full and never loses an item; the mid-floor ender chest
 * refuses while the staging one opens; the saved shape is pinned by a golden fixture.
 *
 * <p>The record is built by hand over three cells laid out in the test level (staging, a floor cell, the
 * pad), the way {@link RescueGameTest} and {@link ClaimFloorGameTest} do. {@link DungeonWorldRules#everywhere}
 * makes the test level count as the dungeon, so an owner counts as inside with a live pack.
 */
public final class PackPotGameTest {

    private static final int SLOT = 9140;

    private static final PlanCell STAGING = new PlanCell(0, 0);
    private static final PlanCell FLOOR = new PlanCell(1, 0);
    private static final PlanCell PAD = new PlanCell(2, 0);

    /** A spot in the floor cell, clear of every lane: local column (3, 4) on the floor. */
    private static BlockPos spotIn(InstanceRecord record) {
        return record.layout.geometry().cellOrigin(FLOOR).offset(3, 1, 4);
    }

    private static InstanceRecord record(GameTestHelper helper, UUID owner) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(STAGING, FLOOR, PAD));
        prepare(level, geometry);
        InstanceLayout layout = new InstanceLayout(origin, geometry, geometry.cellCentre(FLOOR), 0.0f,
                geometry.cellCentre(PAD), geometry.bounds(), 1L, 2, 3, 1, true, Set.of(), 3,
                geometry.cellOrigin(PAD), 0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(SLOT, origin, level.getServer().getTickCount(), layout,
                Set.of(), owner, false);
        record.stagingCellOrigin = geometry.cellOrigin(STAGING);
        record.interval.dungeonId = "pocketdungeons:mineshaft";
        record.interval.nodeId = "adit";
        record.phase = RunSession.Phase.ACTIVE;
        record.members.put(owner, new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        return record;
    }

    /** Loads the chunks, lays a stone floor at the cell origin's height and clears the air above it. */
    private static void prepare(ServerLevel level, PlanGeometry geometry) {
        for (BlockPos cellOrigin : geometry.cellOrigins()) {
            level.getChunk(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4);
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cellOrigin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 1; y <= 6; y++) {
                        level.setBlock(cellOrigin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static void register(InstanceRecord record, ServerPlayer... players) {
        InstanceRegistry.bySlot.put(SLOT, record);
        InstanceRegistry.usedSlots.add(SLOT);
        for (ServerPlayer player : players) {
            InstanceRegistry.byMember.put(player.getUUID(), record);
            PackPots.registerPlayerForTesting(player);
            record.members.putIfAbsent(player.getUUID(),
                    new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        }
    }

    private static void cleanup(MinecraftServer server, ServerPlayer... players) {
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
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, Vec3 at) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.setPos(at);
        return player;
    }

    private static Vec3 above(BlockPos pos) {
        return Vec3.atBottomCenterOf(pos);
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Fills the pack (slots 9 to 35) with a known mix; the hotbar, armour and offhand get their own items. */
    private static void fill(ServerPlayer player) {
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().setItem(4, new ItemStack(Items.BREAD, 12));
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 64));
        player.getInventory().setItem(10, new ItemStack(Items.TORCH, 7));
        player.getInventory().setItem(11, new ItemStack(Items.IRON_SWORD));
        player.getInventory().setItem(20, new ItemStack(Items.STONE_AXE));
        player.getInventory().setItem(35, new ItemStack(Items.DIAMOND_PICKAXE));
        player.getInventory().setItem(36, new ItemStack(Items.IRON_BOOTS));
        player.getInventory().setItem(39, new ItemStack(Items.IRON_HELMET));
        player.getInventory().setItem(40, new ItemStack(Items.SHIELD));
    }

    @GameTest(maxTicks = 40)
    public void aRescueMovesExactlyThe27SlotsIntoAPotWhereTheyFell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        BlockPos fell = spotIn(record);
        player.setPos(above(fell));
        register(record, player);
        fill(player);
        try {
            Instances.rescue(player, record);
            helper.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND_SWORD)
                            && player.getInventory().getItem(4).is(Items.BREAD)
                            && player.getInventory().getItem(4).getCount() == 12,
                    "the hotbar is untouched");
            helper.assertTrue(player.getInventory().getItem(36).is(Items.IRON_BOOTS)
                            && player.getInventory().getItem(39).is(Items.IRON_HELMET)
                            && player.getInventory().getItem(40).is(Items.SHIELD),
                    "armour and offhand are untouched");
            for (int slot = 9; slot <= 35; slot++) {
                helper.assertTrue(player.getInventory().getItem(slot).isEmpty(),
                        "main slot " + slot + " moved into the pot");
            }
            List<PackPot> pots = DungeonLog.forServer(server).packPotsOf(player.getUUID());
            helper.assertTrue(pots.size() == 1, "exactly one pot was made: " + pots.size());
            PackPot pot = pots.get(0);
            helper.assertTrue(pot.pos().isPresent() && pot.pos().get().equals(fell),
                    "it stands on the block they fell on: " + pot.pos());
            helper.assertTrue(level.getBlockState(fell).is(Blocks.DECORATED_POT), "the block is a decorated pot");
            helper.assertTrue(PackPots.isPot(fell), "and a fixture the break rule knows");
            helper.assertTrue(pot.stacks().get(0).is(Items.COBBLESTONE) && pot.stacks().get(0).getCount() == 64
                            && pot.stacks().get(1).is(Items.TORCH) && pot.stacks().get(26).is(Items.DIAMOND_PICKAXE),
                    "every item kept its place in the pot");
            helper.assertTrue(RoomProtection.verdict(player, fell, level.getBlockState(fell))
                            == BreakRule.Verdict.REFUSE_FIXTURE,
                    "the pot cannot be broken");
            helper.assertTrue(PackPots.countFor(server, record) == 1, "the sidebar counts one pot");
            helper.assertTrue(SidebarLines.lines(SidebarDisplay.factsFor(server, record, player.getUUID()),
                            PackPots.countFor(server, record)).stream().anyMatch(l -> l.text().equals("Pots 1")),
                    "the sidebar shows Pots 1");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aLavaSpotSendsThePotToTheNearestDoorOfThatCell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        BlockPos fell = spotIn(record);
        level.setBlock(fell, Blocks.LAVA.defaultBlockState(), 3);
        player.setPos(above(fell));
        register(record, player);
        fill(player);
        try {
            Instances.rescue(player, record);
            List<PackPot> pots = DungeonLog.forServer(server).packPotsOf(player.getUUID());
            helper.assertTrue(pots.size() == 1 && pots.get(0).pos().isPresent(), "a pot was made");
            BlockPos spot = pots.get(0).pos().get();
            BlockPos origin = record.layout.geometry().cellOrigin(FLOOR);
            int lx = spot.getX() - origin.getX();
            int lz = spot.getZ() - origin.getZ();
            helper.assertTrue(!spot.equals(fell), "not in the lava");
            helper.assertTrue(lx >= 1 && lx <= 14 && lz >= 1 && lz <= 14 && spot.getY() == origin.getY() + 1,
                    "inside the same cell, on its floor: " + lx + "," + lz);
            helper.assertTrue(!PackSpots.inLane(lx, lz), "never in a door lane");
            // The fall column (3, 4) is nearest the west door (0, 7.5): the spot hugs the west wall.
            helper.assertTrue(lx <= 4, "it stands beside the nearest door's lane (the west one): " + lx);
            helper.assertTrue(level.getBlockState(spot).is(Blocks.DECORATED_POT), "the block stands there");
            helper.assertTrue(level.getBlockState(fell).is(Blocks.LAVA), "and the lava is left alone");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aPotNeverStandsInADoorLaneOrNearThePad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        BlockPos origin = record.layout.geometry().cellOrigin(FLOOR);
        BlockPos inLane = origin.offset(7, 1, 1);
        helper.assertTrue(PackSpots.inLane(7, 1), "the chosen fall column is in the north lane");
        helper.assertTrue(!PackPots.safeSpot(level, record, inLane), "a lane column is never a pot spot");
        BlockPos chosen = PackPots.chooseSpot(level, record, inLane);
        helper.assertTrue(chosen != null && !PackSpots.inLane(chosen.getX() - origin.getX(),
                chosen.getZ() - origin.getZ()), "the pot is moved out of the lane: " + chosen);
        BlockPos padSpot = record.layout.geometry().cellOrigin(PAD).offset(8, 1, 8);
        helper.assertTrue(!PackPots.safeSpot(level, record, padSpot), "the pad is never a pot spot");
        BlockPos stagingSpot = record.layout.geometry().cellOrigin(STAGING).offset(3, 1, 4);
        helper.assertTrue(!PackPots.safeSpot(level, record, stagingSpot), "the staging room is never a pot spot");
        // A one block corridor: two shut sides would close it.
        BlockPos corridor = origin.offset(3, 1, 12);
        level.setBlock(corridor.east(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(corridor.west(), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(!PackPots.safeSpot(level, record, corridor), "a pot would close a one block gap");
        helper.assertTrue(PackPots.safeSpot(level, record, origin.offset(3, 1, 4)), "an open floor spot is fine");
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void aPartyMemberCanTakeFromThePotAndNobodyCanPutIn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer owner = player(helper, Vec3.ZERO);
        ServerPlayer rider = player(helper, Vec3.ZERO);
        ServerPlayer stranger = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, owner.getUUID());
        BlockPos fell = spotIn(record);
        owner.setPos(above(fell));
        register(record, owner, rider);
        fill(owner);
        try {
            Instances.rescue(owner, record);
            helper.assertTrue(PackPots.onUse(stranger, fell), "a stranger's click is the pot's");
            helper.assertTrue(!(stranger.containerMenu instanceof ChestMenu), "but a stranger sees nothing open");

            helper.assertTrue(PackPots.onUse(rider, fell), "a member's click opens the pot");
            helper.assertTrue(rider.containerMenu instanceof ChestMenu, "as a chest menu");
            helper.assertTrue(rider.containerMenu.getSlot(0).getItem().is(Items.COBBLESTONE), "showing the pack");
            helper.assertTrue(!rider.containerMenu.getSlot(0).mayPlace(new ItemStack(Items.DIRT)),
                    "no slot of the pot takes an item");
            helper.assertTrue(!rider.containerMenu.getSlot(5).mayPlace(new ItemStack(Items.DIRT)),
                    "an empty slot takes nothing either");

            // Shift click the member's own dirt toward the pot: it must not move.
            rider.getInventory().setItem(8, new ItemStack(Items.DIRT, 5));
            int dirtSlot = 27 + 27 + 8;
            rider.containerMenu.clicked(dirtSlot, 0, ContainerInput.QUICK_MOVE, rider);
            helper.assertTrue(rider.getInventory().getItem(8).getCount() == 5, "shift click cannot put dirt in");
            helper.assertTrue(DungeonLog.forServer(server).packPotsOf(owner.getUUID()).get(0).stacks().stream()
                    .noneMatch(s -> s.is(Items.DIRT)), "the pot holds no dirt");

            // Taking works: shift click the cobblestone out.
            rider.containerMenu.clicked(0, 0, ContainerInput.QUICK_MOVE, rider);
            helper.assertTrue(count(rider, Items.COBBLESTONE) == 64, "the member took the stack");
            PackPot after = DungeonLog.forServer(server).packPotsOf(owner.getUUID()).get(0);
            helper.assertTrue(after.stacks().get(0).isEmpty() && after.stacks().get(1).is(Items.TORCH),
                    "the log saw it at once: " + after.stacks().get(0));

            // Emptying the pot removes it and its block.
            for (int slot = 1; slot < 27; slot++) {
                rider.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, rider);
            }
            helper.assertTrue(DungeonLog.forServer(server).packPotsOf(owner.getUUID()).isEmpty(),
                    "an emptied pot is gone from the log");
            helper.assertTrue(level.getBlockState(fell).isAir() && !PackPots.isPot(fell),
                    "and its block is gone");
            helper.assertTrue(count(rider, Items.TORCH) == 7 && count(rider, Items.DIAMOND_PICKAXE) == 1,
                    "nothing was lost on the way out");
        } finally {
            cleanup(server, owner, rider, stranger);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aFloorEndKeepsHalfOfWhatIsLeftInAPot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        BlockPos fell = spotIn(record);
        player.setPos(above(fell));
        register(record, player);
        DungeonWorldRules.everywhere = true;
        fill(player);
        try {
            Instances.rescue(player, record);
            PackPots.settleRun(server, record, PocketDungeonsConfig.potKeepPercent(), true);
            helper.assertTrue(count(player, Items.COBBLESTONE) == 32, "a full stack keeps half: "
                    + count(player, Items.COBBLESTONE));
            helper.assertTrue(count(player, Items.TORCH) == 3, "seven keeps three: " + count(player, Items.TORCH));
            // The three single items are kept every other one, best tier first, by the trip's seed.
            List<PackHalf.Entry> singles = List.of(
                    new PackHalf.Entry(0, "minecraft:diamond_pickaxe", 1, 1, PackPots.tierOf(new ItemStack(Items.DIAMOND_PICKAXE))),
                    new PackHalf.Entry(1, "minecraft:iron_sword", 1, 1, PackPots.tierOf(new ItemStack(Items.IRON_SWORD))),
                    new PackHalf.Entry(2, "minecraft:stone_axe", 1, 1, PackPots.tierOf(new ItemStack(Items.STONE_AXE))));
            int[] expected = PackHalf.keepCounts(singles, record.interval.rewardSalt, 50);
            helper.assertTrue(count(player, Items.DIAMOND_PICKAXE) == expected[0]
                            && count(player, Items.IRON_SWORD) == expected[1]
                            && count(player, Items.STONE_AXE) == expected[2],
                    "the singles keep what the half rule says for the trip's salt");
            helper.assertTrue(DungeonLog.forServer(server).packPotsOf(player.getUUID()).isEmpty(), "the pot is gone");
            helper.assertTrue(level.getBlockState(fell).isAir(), "and so is its block");
            helper.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "the hotbar was never touched");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aFloorEndOverflowGoesToDungeonStorageNotTheFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        BlockPos fell = spotIn(record);
        player.setPos(above(fell));
        register(record, player);
        DungeonWorldRules.everywhere = true;
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 64));
        try {
            Instances.rescue(player, record);
            // The pack is full again before the floor ends: the kept half cannot come back to it.
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
            }
            PackPots.settleRun(server, record, 50, true);
            int stored = 0;
            for (ItemStack stack : DungeonLog.forServer(server).storageOf(player.getUUID())) {
                if (stack.is(Items.COBBLESTONE)) {
                    stored += stack.getCount();
                }
            }
            helper.assertTrue(stored == 32, "the kept half overflowed into Dungeon Storage: " + stored);
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void aFailKeepsHalfOfEveryPotAndEveryPack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer dying = player(helper, Vec3.ZERO);
        ServerPlayer other = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, dying.getUUID());
        BlockPos fell = spotIn(record);
        dying.setPos(above(fell));
        other.setPos(above(fell.north()));
        register(record, dying, other);
        DungeonWorldRules.everywhere = true;
        dying.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        dying.getInventory().setItem(9, new ItemStack(Items.TORCH, 8));
        other.getInventory().setItem(1, new ItemStack(Items.IRON_AXE));
        other.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 20));
        other.getInventory().setItem(10, new ItemStack(Items.BREAD, 3));
        try {
            // An earlier rescue left a pot of torches; the pack is then filled again.
            Instances.rescue(dying, record);
            // The fifth rescue: its pot first (the rescue moved them, so stand where they fell again).
            dying.setPos(above(fell.south()));
            dying.getInventory().setItem(9, new ItemStack(Items.BREAD, 6));
            PackPots.moveToPot(dying, record);
            dying.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 10));
            helper.assertTrue(DungeonLog.forServer(server).packPotsOf(dying.getUUID()).size() == 2,
                    "two pots stand before the fail");

            Instances.failRunOmen(server, record, dying, level.damageSources().generic());

            helper.assertTrue(count(dying, Items.COBBLESTONE) == 5, "the dying player's pack keeps half: "
                    + count(dying, Items.COBBLESTONE));
            helper.assertTrue(count(dying, Items.TORCH) == 4 && count(dying, Items.BREAD) == 3,
                    "each pot kept half and was not halved again by the pack: " + count(dying, Items.TORCH) + ", "
                            + count(dying, Items.BREAD));
            helper.assertTrue(DungeonLog.forServer(server).packPots().isEmpty(), "every pot is settled");
            helper.assertTrue(count(other, Items.COBBLESTONE) == 10 && count(other, Items.BREAD) == 1,
                    "the other member's pack keeps half too: " + count(other, Items.COBBLESTONE) + ", "
                            + count(other, Items.BREAD));
            helper.assertTrue(dying.getInventory().getItem(0).is(Items.DIAMOND_SWORD)
                            && other.getInventory().getItem(1).is(Items.IRON_AXE),
                    "both hotbars are untouched");
        } finally {
            cleanup(server, dying, other);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aSecondFailPathDoesNotHalveAPackTwice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        register(record, player);
        DungeonWorldRules.everywhere = true;
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 40));
        try {
            RunLifecycle.bankHaul(server, record, player.getUUID(), RunLifecycle.BankContext.FAIL);
            RunLifecycle.bankHaul(server, record, player.getUUID(), RunLifecycle.BankContext.FAIL);
            helper.assertTrue(count(player, Items.COBBLESTONE) == 20,
                    "two fail paths still halve once: " + count(player, Items.COBBLESTONE));
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aRestartReturnsAPotInFullToDungeonStorage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        BlockPos fell = spotIn(record);
        player.setPos(above(fell));
        register(record, player);
        DungeonWorldRules.everywhere = true;
        fill(player);
        try {
            Instances.rescue(player, record);
            helper.assertTrue(DungeonLog.forServer(server).packPotsOf(player.getUUID()).size() == 1, "a pot stands");
            // The pot is in the saved log; a restart forgets the run and the in-memory index.
            PackPots.restartForTesting();
            InstanceRegistry.bySlot.remove(SLOT);
            PackPots.onJoin(server, player);
            helper.assertTrue(DungeonLog.forServer(server).packPotsOf(player.getUUID()).isEmpty(),
                    "the pot is settled on the next join");
            int cobble = 0, torches = 0, picks = 0;
            for (ItemStack stack : DungeonLog.forServer(server).storageOf(player.getUUID())) {
                cobble += stack.is(Items.COBBLESTONE) ? stack.getCount() : 0;
                torches += stack.is(Items.TORCH) ? stack.getCount() : 0;
                picks += stack.is(Items.DIAMOND_PICKAXE) ? stack.getCount() : 0;
            }
            helper.assertTrue(cobble == 64 && torches == 7 && picks == 1,
                    "everything came back in full, to Dungeon Storage: " + cobble + ", " + torches + ", " + picks);
            helper.assertTrue(level.getBlockState(fell).isAir(), "the pot's block is gone");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void whatCannotBeCarriedStaysOwedUntilThereIsRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        BlockPos fell = spotIn(record);
        player.setPos(above(fell));
        register(record, player);
        DungeonWorldRules.everywhere = true;
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 64));
        player.getInventory().setItem(10, new ItemStack(Items.TORCH, 7));
        DungeonLog log = DungeonLog.forServer(server);
        try {
            Instances.rescue(player, record);
            // Storage and the pack are both full when the owner next joins.
            List<ItemStack> full = new ArrayList<>();
            for (int i = 0; i < RunStorage.SLOTS; i++) {
                full.add(new ItemStack(Items.DIRT, 64));
            }
            log.setStorage(player.getUUID(), full);
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
            }
            PackPots.restartForTesting();
            InstanceRegistry.bySlot.remove(SLOT);
            PackPots.onJoin(server, player);
            List<PackPot> owed = log.packPotsOf(player.getUUID());
            helper.assertTrue(owed.size() == 1 && owed.get(0).owed(), "the remainder is kept in an owed record");
            int cobble = 0, torches = 0;
            for (ItemStack stack : owed.get(0).stacks()) {
                cobble += stack.is(Items.COBBLESTONE) ? stack.getCount() : 0;
                torches += stack.is(Items.TORCH) ? stack.getCount() : 0;
            }
            helper.assertTrue(cobble == 64 && torches == 7, "nothing was deleted: " + cobble + ", " + torches);

            // Room appears; the next join delivers it.
            log.setStorage(player.getUUID(), List.of());
            PackPots.onJoin(server, player);
            helper.assertTrue(log.packPotsOf(player.getUUID()).isEmpty(), "the debt is paid");
            int stored = 0;
            for (ItemStack stack : log.storageOf(player.getUUID())) {
                stored += stack.is(Items.COBBLESTONE) || stack.is(Items.TORCH) ? stack.getCount() : 0;
            }
            helper.assertTrue(stored == 71, "all of it reached Dungeon Storage: " + stored);
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aMidFloorEnderChestRefusesAndTheStagingOneOpens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        register(record, player);
        try {
            var chest = RunStorage.stationState(Direction.NORTH);
            BlockPos midFloor = spotIn(record);
            helper.assertTrue(RunStorage.onUse(player, chest, midFloor, true), "the click is swallowed");
            helper.assertTrue(!(player.containerMenu instanceof ChestMenu), "but nothing opens mid floor");
            BlockPos atTheDoors = record.layout.geometry().cellOrigin(STAGING).offset(5, 1, 5);
            helper.assertTrue(RunStorage.onUse(player, chest, atTheDoors, true), "the staging chest answers");
            helper.assertTrue(player.containerMenu instanceof ChestMenu, "and opens the storage");
            player.closeContainer();
            // Outside a trip nothing changes.
            InstanceRegistry.byMember.remove(player.getUUID());
            helper.assertTrue(RunStorage.onUse(player, chest, midFloor, true) && player.containerMenu instanceof ChestMenu,
                    "with no trip an ender chest opens anywhere in the dungeon");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void actZeroAndAdminRunsMakeNoPots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = player(helper, Vec3.ZERO);
        InstanceRecord record = record(helper, player.getUUID());
        BlockPos fell = spotIn(record);
        player.setPos(above(fell));
        register(record, player);
        try {
            record.interval.dungeonId = "pocketdungeons:first_night";
            fill(player);
            helper.assertTrue(!PackPots.moveToPot(player, record), "Act 0 makes no pot");
            helper.assertTrue(player.getInventory().getItem(9).is(Items.COBBLESTONE), "the pack stays");
            record.interval.dungeonId = "pocketdungeons:mineshaft";
            InstanceRecord visit = record(helper, player.getUUID());
            visit.visitInstance = true;
            helper.assertTrue(!PackPots.moveToPot(player, visit), "a visit makes no pot");
            helper.assertTrue(!PackPots.appliesTo(null), "no record, no pot");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    /**
     * The saved shape of a pot, pinned: this exact JSON is what {@code dungeon_log.dat} holds for one, it
     * decodes to the values below, re-encoding it gives back the same JSON, and a record written before the
     * optional fields (or a save with no pots at all) still loads. A pot is player data.
     */
    private static final String GOLDEN = "{\"players\":[],\"pack_pots\":[{"
            + "\"id\":\"00000000-0000-0000-0000-0000000000c1\","
            + "\"owner\":\"00000000-0000-0000-0000-00000000000a\","
            + "\"run_slot\":3,\"run_tick\":1200,\"session\":77,\"floor\":2,"
            + "\"owner_name\":\"Alex\",\"pos\":[10,64,-5],"
            + "\"stacks\":[{\"id\":\"minecraft:cobblestone\",\"count\":64},{},"
            + "{\"id\":\"minecraft:iron_sword\",\"count\":1},{},{},{},{},{},{},{},{},{},{},{},"
            + "{},{},{},{},{},{},{},{},{},{},{},{},"
            + "{\"id\":\"minecraft:torch\",\"count\":7}]}]}";

    @GameTest(maxTicks = 20)
    public void thePotRecordHasAGoldenFixture(GameTestHelper helper) {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString(GOLDEN);
        DungeonLog log = DungeonLog.CODEC.decode(com.mojang.serialization.JsonOps.INSTANCE, json)
                .result().orElseThrow().getFirst();
        List<PackPot> pots = log.packPotsOf(owner);
        helper.assertTrue(pots.size() == 1, "one pot decodes");
        PackPot pot = pots.get(0);
        helper.assertTrue(pot.id().equals(UUID.fromString("00000000-0000-0000-0000-0000000000c1")), "id");
        helper.assertTrue(pot.runSlot() == 3 && pot.runTick() == 1200L && pot.session() == 77L && pot.floor() == 2,
                "run, session and floor");
        helper.assertTrue(pot.ownerName().equals("Alex") && pot.dim().equals("pocketdungeons:void"),
                "the name, and the dungeon dimension when none is written");
        helper.assertTrue(pot.pos().equals(Optional.of(new BlockPos(10, 64, -5))), "position");
        helper.assertTrue(pot.stacks().size() == PackPot.SLOTS, "27 slots");
        helper.assertTrue(pot.stacks().get(0).is(Items.COBBLESTONE) && pot.stacks().get(0).getCount() == 64
                        && pot.stacks().get(2).is(Items.IRON_SWORD) && pot.stacks().get(26).is(Items.TORCH)
                        && pot.stacks().get(26).getCount() == 7 && pot.stacks().get(1).isEmpty(),
                "every stack keeps its place");
        com.google.gson.JsonElement written = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, log).result().orElseThrow();
        helper.assertTrue(written.getAsJsonObject().get("pack_pots").equals(json.getAsJsonObject().get("pack_pots")),
                "re-encoding gives the same pot JSON: " + written.getAsJsonObject().get("pack_pots"));

        // Older or sparser shapes: only the keys a pot cannot do without; everything else defaults.
        String sparse = "{\"players\":[],\"pack_pots\":[{\"id\":\"00000000-0000-0000-0000-0000000000c2\","
                + "\"owner\":\"00000000-0000-0000-0000-00000000000a\",\"run_slot\":-1,\"run_tick\":0,"
                + "\"dim\":\"minecraft:overworld\","
                + "\"stacks\":[{\"id\":\"minecraft:bread\",\"count\":3}]}]}";
        DungeonLog old = DungeonLog.CODEC.decode(com.mojang.serialization.JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString(sparse)).result().orElseThrow().getFirst();
        PackPot owed = old.packPotsOf(owner).get(0);
        helper.assertTrue(owed.owed() && owed.pos().isEmpty() && owed.session() == 0L && owed.dim().equals("minecraft:overworld") && owed.stacks().size() == 27
                && owed.stacks().get(0).is(Items.BREAD), "a sparse record loads as an owed pot, padded to 27");
        DungeonLog none = DungeonLog.CODEC.decode(com.mojang.serialization.JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("{\"players\":[]}")).result().orElseThrow().getFirst();
        helper.assertTrue(none.packPots().isEmpty(), "a save written before pots loads with none");
        helper.assertTrue(!DungeonLog.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, none).result()
                        .orElseThrow().getAsJsonObject().has("pack_pots"),
                "and writes none until one is made");
        helper.succeed();
    }
}
