package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wild lodestones phase 2 (tasks 2.1 to 2.9): the build, the started floor, enter and leave, the inventory bypass, the
 * member sweep and the PvP rules.
 *
 * <p>The gametest server has no dungeon dimension (DISCOVERIES trap 18), so the real {@code Instances.admit} (which
 * teleports into {@code pocketdungeons:void}) cannot run here. These tests build the dungeon in the test level far from
 * every other test, add members to the record the way {@code admit} records them, and drive everything else for real:
 * the planner, the stamp, {@code startWildFloor}, {@code leave}, the tick sweep and the rules. Where a player lands
 * after a teleport into the dungeon dimension stays a live check.
 */
public final class WildRunGameTest {

    private static final BlockPos ORIGIN = new BlockPos(14400, 120, 14400);
    private static final int SLOT = 9981;

    private static WildState.Lodestone lodestone(ServerLevel level, WildDungeons.Offering offering, String id, int lvl) {
        return new WildState.Lodestone(id, WildLodestones.dimensionKey(level), 5, 70, 5, offering.id(), lvl,
                1000L, 1000L + 7L * 86400L);
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

    /**
     * Every shipped offering plans, stamps and starts: the record is ACTIVE, the trip names the node, the dungeon is
     * known to {@code TripView}, the arrival exit stands, and the floor has something to clear (a trial spawner or a
     * non spawner objective). A floor with nothing to clear could never end, so the phase 3 clear guard would leave it
     * uncleared forever.
     */
    @GameTest(maxTicks = 1200)
    public void everyOfferingBuildsAFloorThatCanClear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        List<WildDungeons.Offering> offerings = WildDungeons.all();
        helper.assertTrue(!offerings.isEmpty(), "the shipped offerings load");
        List<String> problems = new ArrayList<>();
        for (WildDungeons.Offering offering : offerings) {
            InstanceRecord record = null;
            try {
                WildState.Lodestone lode = lodestone(level, offering, "t-" + offering.id(), 2);
                WildRun.PlannedFloor planned = WildRun.planFor(level, lode, offering);
                if (planned == null) {
                    problems.add(offering.id() + ": no plan (" + WildRun.lastRefusal() + ")");
                    continue;
                }
                InstanceRegistry.usedSlots.add(SLOT);
                record = WildRun.stampAt(server, level, lode, planned, SLOT, ORIGIN);
                if (record == null) {
                    problems.add(offering.id() + ": no record (" + WildRun.lastRefusal() + ")");
                    continue;
                }
                if (record.phase != RunSession.Phase.ACTIVE) {
                    problems.add(offering.id() + ": phase " + record.phase);
                }
                if (!record.wild || !record.untimed || record.owner.getMostSignificantBits() != 0) {
                    problems.add(offering.id() + ": not a wild untimed nil owner record");
                }
                if (TripView.def(record) == null) {
                    problems.add(offering.id() + ": TripView.def is null");
                }
                if (!offering.node().equals(record.interval.nodeId) || record.floor.theme == null) {
                    problems.add(offering.id() + ": trip node/theme not set (" + record.interval.nodeId + ", "
                            + record.floor.theme + ")");
                }
                if (record.layout.keystoneLevel() != 2) {
                    problems.add(offering.id() + ": level " + record.layout.keystoneLevel());
                }
                boolean clearable = !record.layout.trialSpawners().isEmpty()
                        || FloorObjectives.of(record) != FloorObjective.SPAWNERS;
                if (!clearable) {
                    problems.add(offering.id() + ": nothing to clear (no spawners, spawner objective)");
                }
                if (!WildLodestones.hasExit(lode.id, false)) {
                    problems.add(offering.id() + ": no arrival exit block");
                }
                if (Instances.instanceAt(record.layout.entrance()) != record) {
                    problems.add(offering.id() + ": instanceAt does not find the record");
                }
            } finally {
                cleanUp(level, record);
                InstanceRegistry.usedSlots.remove(SLOT);
            }
        }
        helper.assertTrue(problems.isEmpty(), "offering problems: " + problems);
        helper.succeed();
    }

    /** Two players share one record, leave keeps the record up and loses nothing, even from a full pack and cursor. */
    @SuppressWarnings("removal")
    @GameTest(maxTicks = 600)
    public void twoPlayersShareOneRecordAndLeavingLosesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        WildDungeons.Offering offering = WildDungeons.byId("pocketdungeons:boiler_hall");
        helper.assertTrue(offering != null, "boiler_hall loads");
        WildState.Lodestone lode = lodestone(level, offering, "w-share", 1);
        WildState state = new WildState();
        state.add(lode);
        WildStore.setForTesting(state);
        WildMode.setActiveForTesting(true);
        InstanceRecord record = null;
        try {
            WildRun.PlannedFloor planned = WildRun.planFor(level, lode, offering);
            helper.assertTrue(planned != null, "plans");
            InstanceRegistry.usedSlots.add(SLOT);
            record = WildRun.stampAt(server, level, lode, planned, SLOT, ORIGIN);
            helper.assertTrue(record != null, "stamps");
            helper.assertTrue(WildRun.liveRecord(lode.id) == record, "the lodestone finds its record");
            helper.assertTrue(WildRun.insideCount(lode.id) == 0, "empty at first");

            ServerPlayer a = helper.makeMockServerPlayerInLevel();
            ServerPlayer b = helper.makeMockServerPlayerInLevel();
            a.setGameMode(GameType.SURVIVAL);
            b.setGameMode(GameType.SURVIVAL);
            for (int i = 0; i < a.getInventory().getContainerSize(); i++) {
                a.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 7 + i % 5));
            }
            ItemStack carried = new ItemStack(Items.DIAMOND, 3);
            a.containerMenu.setCarried(carried.copy());
            int before = fingerprint(a);
            for (ServerPlayer p : List.of(a, b)) {
                record.members.put(p.getUUID(), new ReturnPoint(Level.OVERWORLD, p.position(), 0.0f, 0.0f));
                InstanceRegistry.byMember.put(p.getUUID(), record);
            }
            helper.assertTrue(WildRun.insideCount(lode.id) == 2, "both are inside one record");

            WildRun.leave(a);
            helper.assertTrue(!record.members.containsKey(a.getUUID()) && !InstanceRegistry.byMember.containsKey(a.getUUID()),
                    "a left");
            helper.assertTrue(record.members.containsKey(b.getUUID()), "b stays");
            helper.assertTrue(InstanceRegistry.bySlot.get(SLOT) == record, "the record stays up");
            helper.assertValueEqual(before, fingerprint(a), "the pack is untouched");
            helper.assertTrue(ItemStack.matches(carried, a.containerMenu.getCarried()), "the cursor stack is kept");

            WildRun.leave(b);
            helper.assertTrue(record.members.isEmpty() && InstanceRegistry.bySlot.get(SLOT) == record,
                    "an empty wild record stays up");
        } finally {
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            cleanUp(level, record);
        }
        helper.succeed();
    }

    private static int fingerprint(ServerPlayer player) {
        int folded = 17;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            folded = 31 * folded + stack.getItem().hashCode() * 7 + stack.getCount();
        }
        return folded;
    }

    /** The watch tick drops a member who is not in the dungeon dimension and leaves the record standing. */
    @SuppressWarnings("removal")
    @GameTest(maxTicks = 600)
    public void tickDropsMembersWhoLeftTheDimension(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        WildDungeons.Offering offering = WildDungeons.byId("pocketdungeons:boiler_hall");
        WildState.Lodestone lode = lodestone(level, offering, "w-tick", 1);
        WildState state = new WildState();
        state.add(lode);
        WildStore.setForTesting(state);
        WildMode.setActiveForTesting(true);
        InstanceRecord record = null;
        try {
            InstanceRegistry.usedSlots.add(SLOT);
            record = WildRun.stampAt(server, level, lode, WildRun.planFor(level, lode, offering), SLOT, ORIGIN);
            helper.assertTrue(record != null, "stamps");
            ServerPlayer a = helper.makeMockServerPlayerInLevel();
            record.members.put(a.getUUID(), new ReturnPoint(Level.OVERWORLD, a.position(), 0.0f, 0.0f));
            InstanceRegistry.byMember.put(a.getUUID(), record);
            lode.markInProgress();
            WildRun.tick(server, record, level.getGameTime(), 20);
            helper.assertTrue(record.members.isEmpty() && !InstanceRegistry.byMember.containsKey(a.getUUID()),
                    "the member standing outside the dungeon dimension is detached");
            helper.assertTrue(InstanceRegistry.bySlot.get(SLOT) == record, "the record stays");
            WildRun.tick(server, record, level.getGameTime(), 20);
        } finally {
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            cleanUp(level, record);
        }
        helper.succeed();
    }

    /** The entry checks refuse a forged or impossible enter before anything is built. */
    @SuppressWarnings("removal")
    @GameTest
    public void entryIsRefusedWhenForgedOrImpossible(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WildDungeons.loadFrom(level.getServer());
        WildDungeons.Offering offering = WildDungeons.byId("pocketdungeons:boiler_hall");
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        BlockPos near = helper.absolutePos(new BlockPos(2, 2, 2));
        p.teleportTo(level, near.getX() + 0.5, near.getY(), near.getZ() + 0.5, Set.of(), 0.0f, 0.0f, false);
        WildState.Lodestone lode = new WildState.Lodestone("w-entry", WildLodestones.dimensionKey(level), near.getX() + 1,
                near.getY(), near.getZ() + 1, offering.id(), 1, 1000L, 90000L);
        WildMode.setActiveForTesting(false);
        helper.assertTrue(WildRun.refuseEntry(p, lode) != null, "campaign mode refuses");
        WildMode.setActiveForTesting(true);
        try {
            helper.assertTrue(WildRun.refuseEntry(p, lode) == null, "a player beside the lodestone may enter");
            helper.assertTrue(WildRun.refuseEntry(p, null) != null, "a vanished lodestone refuses");
            WildState.Lodestone far = new WildState.Lodestone("w-far", WildLodestones.dimensionKey(level),
                    near.getX() + 100, near.getY(), near.getZ(), offering.id(), 1, 1000L, 90000L);
            helper.assertTrue(WildRun.refuseEntry(p, far) != null, "a player 100 blocks away is refused");
            WildState.Lodestone other = new WildState.Lodestone("w-nether", "minecraft:the_nether", near.getX(),
                    near.getY(), near.getZ(), offering.id(), 1, 1000L, 90000L);
            helper.assertTrue(WildRun.refuseEntry(p, other) != null, "another dimension is refused");
            InstanceRegistry.byMember.put(p.getUUID(), InstanceRecord.forWild(5, BlockPos.ZERO, 0L, null, Set.of(), "x"));
            helper.assertTrue(WildRun.refuseEntry(p, lode) != null, "a player already in a record is refused");
        } finally {
            InstanceRegistry.byMember.remove(p.getUUID());
            WildMode.setActiveForTesting(null);
        }
        helper.succeed();
    }

    /**
     * Wild mode never swaps an inventory: reconcile leaves a full pack and a cursor alone even when the player stands
     * in the dungeon dimension (stood in for by the test level here), and a cursor crossing out is not lifted.
     */
    @SuppressWarnings("removal")
    @GameTest
    public void inventoryBypassLeavesThePackAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            p.getInventory().setItem(i, new ItemStack(Items.IRON_INGOT, 1 + i % 9));
        }
        ItemStack carried = new ItemStack(Items.EMERALD, 5);
        p.containerMenu.setCarried(carried.copy());
        int before = fingerprint(p);
        InventorySwap.Probe.useDimensionForTesting(level.dimension());
        WildMode.setActiveForTesting(true);
        try {
            InventorySwap.reconcile(p);
            helper.assertValueEqual(before, fingerprint(p), "reconcile does not stash the pack in wild mode");
            helper.assertTrue(ItemStack.matches(carried, p.containerMenu.getCarried()), "the cursor stays");
            InventorySwap.liftCursorBeforeCrossing(level.getServer(), p, Level.NETHER);
            helper.assertTrue(ItemStack.matches(carried, p.containerMenu.getCarried()),
                    "crossing out does not park the cursor stack");
        } finally {
            WildMode.setActiveForTesting(null);
            InventorySwap.Probe.useDimensionForTesting(null);
        }
        helper.succeed();
    }

    /** Spawn protection stops damage, an attack by the protected player ends it, then they can be hurt. */
    @SuppressWarnings("removal")
    @GameTest
    public void spawnProtectionHoldsThenEndsOnAttack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer victim = helper.makeMockServerPlayerInLevel();
        ServerPlayer other = helper.makeMockServerPlayerInLevel();
        WildMode.setActiveForTesting(true);
        try {
            long until = level.getServer().overworld().getGameTime() + 200;
            WildRules.protectForTesting(victim.getUUID(), until);
            var source = level.damageSources().generic();
            helper.assertTrue(!ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(victim, source, 2.0f),
                    "a protected player takes no damage");
            helper.assertTrue(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(other, source, 2.0f),
                    "an unprotected player takes damage (PvP is on)");
            AttackEntityCallback.EVENT.invoker().interact(victim, level, InteractionHand.MAIN_HAND, other, null);
            helper.assertTrue(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(victim, source, 2.0f),
                    "attacking ends the protection");
        } finally {
            WildRules.clearForTesting();
            WildMode.setActiveForTesting(null);
        }
        helper.succeed();
    }

    /** Beds and respawn anchors are blocked; the item half needs the dungeon dimension and is a live check. */
    @SuppressWarnings("removal")
    @GameTest
    public void killToolBlocksAreRecognised(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(WildRules.blocksBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.parse("minecraft:red_bed"))), "beds are blocked");
        helper.assertTrue(WildRules.blocksBlock(net.minecraft.world.level.block.Blocks.RESPAWN_ANCHOR),
                "respawn anchors are blocked");
        helper.assertTrue(!WildRules.blocksBlock(net.minecraft.world.level.block.Blocks.CHEST), "chests are not");
        helper.assertTrue(!WildRules.blocksItem(p, Items.ENDER_PEARL), "outside the dungeon a pearl is fine");
        helper.succeed();
    }
}
