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

    /**
     * Each stamping test gets its own slot and its own corner of the world. The tests of a class run in one world at
     * the same time, and one test's cleanup releases the force loaded chunks of the cells it clears: sharing a corner
     * let one test unload another's dungeon mid run.
     */
    private static int slotOf(int test) {
        return 9981 + test;
    }

    private static BlockPos originOf(int test) {
        return new BlockPos(14400 + 1024 * test, 120, 14400);
    }

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
        int mostSpawners = 0;
        for (WildDungeons.Offering offering : offerings) {
            InstanceRecord record = null;
            try {
                WildState.Lodestone lode = lodestone(level, offering, "t-" + offering.id(), 2);
                WildRun.PlannedFloor planned = WildRun.planFor(level, lode, offering);
                if (planned == null) {
                    problems.add(offering.id() + ": no plan (" + WildRun.lastRefusal() + ")");
                    continue;
                }
                InstanceRegistry.usedSlots.add(slotOf(0));
                record = WildRun.stampAt(server, level, lode, planned, slotOf(0), originOf(0));
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
                // No clear path is required: players bring overworld items to solve a floor (owner, 2026-10-31).
                if (offering.type().equals(WildDungeons.LONG_DUNGEON) && record.layout.roomCount() < 8) {
                    problems.add(offering.id() + ": a long dungeon has only " + record.layout.roomCount() + " rooms");
                }
                // Wild floors are longer than a campaign floor (owner, 2026-11-04).
                int shortest = PocketDungeonsConfig.pathLengthMin() + offering.pathBonus();
                if (record.layout.roomCount() < shortest) {
                    problems.add(offering.id() + ": only " + record.layout.roomCount() + " rooms, wanted " + shortest);
                }
                mostSpawners = Math.max(mostSpawners, record.layout.trialSpawners().size());
                // Every wild dungeon ends in a boss (decision 2026-11-04).
                if (FinaleWave.finaleOf(record) == null && !FinaleScripts.has(offering.dungeon())) {
                    problems.add(offering.id() + ": no boss at the end");
                }
                for (WildDungeons.Bonus bonus : offering.bonusRewards()) {
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                            net.minecraft.resources.Identifier.parse(bonus.item()));
                    if (item == Items.AIR) {
                        problems.add(offering.id() + ": bonus item " + bonus.item() + " does not exist");
                    }
                }
                if (!WildLodestones.hasExit(lode.id, false)) {
                    problems.add(offering.id() + ": no arrival exit block");
                }
                if (Instances.instanceAt(record.layout.entrance()) != record) {
                    problems.add(offering.id() + ": instanceAt does not find the record");
                }
            } finally {
                cleanUp(level, record);
                InstanceRegistry.usedSlots.remove(slotOf(0));
            }
        }
        helper.assertTrue(problems.isEmpty(), "offering problems: " + problems);
        // The campaign's first floor cap (two spawners) no longer reaches a wild floor (kingdom server, 2026-11-04).
        helper.assertTrue(mostSpawners > PocketDungeonsConfig.firstFloorMaxEncounters(),
                "no wild floor has more than " + PocketDungeonsConfig.firstFloorMaxEncounters() + " trial spawners");
        helper.succeed();
    }

    /** An ominous lodestone plans its next run higher and with the Ominous affix (decision 2026-11-04). */
    @GameTest(maxTicks = 400)
    public void anOminousLodestonePlansHigherAndOminous(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WildDungeons.loadFrom(level.getServer());
        WildDungeons.Offering offering = WildDungeons.all().get(0);
        WildState.Lodestone lode = lodestone(level, offering, "t-omen", 3);
        WildRun.PlannedFloor plain = WildRun.planFor(level, lode, offering);
        lode.ominous = true;
        WildRun.PlannedFloor ominous = WildRun.planFor(level, lode, offering);
        helper.assertTrue(plain != null && ominous != null, "both plan: " + WildRun.lastRefusal());
        helper.assertTrue(plain.offer().level() == 3, "a plain lodestone plans at its own level");
        helper.assertTrue(ominous.affixes().contains(AffixIds.OMINOUS)
                        && ominous.offer().level() == 3 + WildOmen.EXTRA_LEVELS,
                "an ominous one plans " + WildOmen.EXTRA_LEVELS + " levels higher with the Ominous affix");
        helper.succeed();
    }

    /**
     * Every shipped offering also stamps with Blackout added. A room with no content id once made the Blackout cell
     * choice throw (asking an immutable set about null), so a wild deep_quarry with Blackout could never be built.
     */
    @GameTest(maxTicks = 1200)
    public void everyOfferingStampsUnderBlackout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        List<String> problems = new ArrayList<>();
        for (WildDungeons.Offering offering : WildDungeons.all()) {
            InstanceRecord record = null;
            try {
                WildState.Lodestone lode = lodestone(level, offering, "b-" + offering.id(), 2);
                WildRun.PlannedFloor planned = WildRun.planFor(level, lode, offering);
                if (planned == null) {
                    problems.add(offering.id() + ": no plan (" + WildRun.lastRefusal() + ")");
                    continue;
                }
                Set<String> affixes = new LinkedHashSet<>(planned.affixes());
                affixes.add(AffixIds.BLACKOUT);
                WildRun.PlannedFloor dark = new WildRun.PlannedFloor(planned.offer(), planned.plan(), planned.theme(),
                        Set.copyOf(affixes), planned.themeId(), planned.record());
                InstanceRegistry.usedSlots.add(slotOf(4));
                record = WildRun.stampAt(server, level, lode, dark, slotOf(4), originOf(4));
                if (record == null) {
                    problems.add(offering.id() + ": no record under Blackout (" + WildRun.lastRefusal() + ")");
                }
            } catch (RuntimeException e) {
                problems.add(offering.id() + ": " + e);
            } finally {
                cleanUp(level, record);
                InstanceRegistry.usedSlots.remove(slotOf(4));
            }
        }
        helper.assertTrue(problems.isEmpty(), "Blackout stamping problems: " + problems);
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
            InstanceRegistry.usedSlots.add(slotOf(1));
            record = WildRun.stampAt(server, level, lode, planned, slotOf(1), originOf(1));
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
            helper.assertTrue(InstanceRegistry.bySlot.get(slotOf(1)) == record, "the record stays up");
            helper.assertValueEqual(before, fingerprint(a), "the pack is untouched");
            helper.assertTrue(ItemStack.matches(carried, a.containerMenu.getCarried()), "the cursor stack is kept");

            WildRun.leave(b);
            helper.assertTrue(record.members.isEmpty() && InstanceRegistry.bySlot.get(slotOf(1)) == record,
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

    /**
     * The rule behind the stray sweep (owner, 2026-10-31: an enderman that lands on the roof must not strand its
     * spawner): a point counts as in the fight only inside a cell's own column and height. Above the roof, off the side
     * and far below the floor are all strays. Checked on plain positions, because a test that spawns mobs into a far
     * away force loaded layout depends on how long the test world takes to load those chunks; whether the sweep then
     * removes the mob is a live check.
     */
    @GameTest
    public void strayRuleCountsOnlyCellVolumes(GameTestHelper helper) {
        java.util.List<BlockPos> cells = java.util.List.of(new BlockPos(1600, 120, 1600), new BlockPos(1616, 120, 1600),
                new BlockPos(1616, 120, 1616));
        double mid = RoomGeometry.CELL / 2.0;
        helper.assertTrue(WildRun.inCell(cells, new net.minecraft.world.phys.Vec3(1600 + mid, 121, 1600 + mid)),
                "the middle of a cell is inside");
        helper.assertTrue(WildRun.inCell(cells, new net.minecraft.world.phys.Vec3(1616 + mid, 121, 1616 + mid)),
                "the middle of another cell is inside");
        helper.assertTrue(!WildRun.inCell(cells, new net.minecraft.world.phys.Vec3(1600 + mid, 120 + RoomGeometry.CEILING_Y + 6,
                1600 + mid)), "above the roof is a stray");
        helper.assertTrue(!WildRun.inCell(cells, new net.minecraft.world.phys.Vec3(1600 + mid, 120 - 20, 1600 + mid)),
                "far below the floor is a stray");
        helper.assertTrue(!WildRun.inCell(cells, new net.minecraft.world.phys.Vec3(1600 - 3, 121, 1600 + mid)),
                "outside the side of the layout is a stray");
        helper.assertTrue(!WildRun.inCell(cells, new net.minecraft.world.phys.Vec3(1600 + mid, 121, 1616 + mid)),
                "a gap in the layout (no cell there) is a stray");
        helper.succeed();
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
            InstanceRegistry.usedSlots.add(slotOf(3));
            record = WildRun.stampAt(server, level, lode, WildRun.planFor(level, lode, offering), slotOf(3), originOf(3));
            helper.assertTrue(record != null, "stamps");
            ServerPlayer a = helper.makeMockServerPlayerInLevel();
            record.members.put(a.getUUID(), new ReturnPoint(Level.OVERWORLD, a.position(), 0.0f, 0.0f));
            InstanceRegistry.byMember.put(a.getUUID(), record);
            lode.markInProgress();
            WildRun.tick(server, record, level.getGameTime(), 20);
            helper.assertTrue(record.members.isEmpty() && !InstanceRegistry.byMember.containsKey(a.getUUID()),
                    "the member standing outside the dungeon dimension is detached");
            helper.assertTrue(InstanceRegistry.bySlot.get(slotOf(3)) == record, "the record stays");
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


    /**
     * An enderman in the dungeon dimension (stood in for by {@code DungeonWorldRules.everywhere}) cannot teleport: the
     * private teleport method refuses and the enderman stays where it is.
     */
    @GameTest
    public void endermenCannotTeleportInTheDungeon(GameTestHelper helper) throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        var enderman = helper.spawn(net.minecraft.world.entity.EntityTypes.ENDERMAN, new BlockPos(3, 1, 3));
        var before = enderman.position();
        var teleport = net.minecraft.world.entity.monster.Enderman.class.getDeclaredMethod("teleport", double.class,
                double.class, double.class);
        teleport.setAccessible(true);
        DungeonWorldRules.everywhere = true;
        try {
            boolean moved = (boolean) teleport.invoke(enderman, before.x + 4, before.y, before.z + 4);
            helper.assertTrue(!moved, "the dungeon refuses the teleport");
            helper.assertTrue(enderman.position().equals(before), "the enderman stays where it was");
        } finally {
            DungeonWorldRules.everywhere = false;
        }
        enderman.discard();
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
