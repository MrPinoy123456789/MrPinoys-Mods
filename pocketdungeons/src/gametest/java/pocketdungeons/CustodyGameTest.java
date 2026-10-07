package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M63 custody scenarios: no tested transition may silently lose or duplicate a
 * player's inventory.
 *
 * <p>This lives in package {@code pocketdungeons}, not
 * {@code pocketdungeons.gametest} where {@link pocketdungeons.gametest.InventorySwapGameTest}
 * sits, and that is deliberate. The custody state these scenarios assert on
 * ({@link DungeonLog#orphanOf}, {@link InventorySwap.OrphanRecord},
 * {@link InventoryJournal}) is package-private, and the alternative
 * to sharing the package is opening six production members to the world so a
 * test can read them. Split packages across two source sets are legal and both
 * halves land in the same classloader in dev, so this compiles and runs
 * without a single visibility change to the mod.
 *
 * <h2>What these assert, and why it is counting rather than comparing</h2>
 *
 * <p>{@link pocketdungeons.gametest.InventorySwapGameTest} already proves the
 * swap round trips slot for slot. These scenarios ask the different question
 * M63 is about: across a transition that goes wrong, is every item still
 * somewhere. So the assertions are exact per-item totals summed over every
 * place an item can legitimately be at once (live inventory, cursor, the stash
 * record, the dungeon inventory record, and item entities dropped at the
 * player's feet), not slot-for-slot equality. An item that moved from a slot
 * to the floor is conserved; an item that is in both a slot and the record is
 * duplicated, and that is the failure this milestone exists to catch.
 *
 * <p>Not covered here: the identity of {@code pocketdungeons:void} (the
 * scenarios stand the nether or the overworld in for it), a keystone in slot
 * 0 on a live entry (a keystone holder's remote is also reconciled into their
 * survival inventory by the keystone watcher, which a conservation count
 * cannot tell apart; the pure core covers the slot 0 rule), and the kit
 * top-up's settlement path, which needs a live instance record (its math is
 * {@code KitTopUpTest}; the rest is a live checklist row).
 *
 * <p>The dungeon dimension is the nether here, for the reason
 * {@link InventorySwap.Probe#useDimensionForTesting} spells out: a
 * {@code GameTestServer} never creates a datapack dimension, so the identity of
 * {@code pocketdungeons:void} is a live-checklist row and everything else runs
 * for real.
 */
@SuppressWarnings("removal")
public final class CustodyGameTest {

    /**
     * Entry and exit with every slot occupied and a stack on the cursor.
     *
     * <p>The full-inventory case is the one that breaks: an exit has to put 42
     * slots back into a player who may already be holding something, and the
     * cursor stack has no slot of its own to return to. Nothing may be voided
     * to make it fit.
     */
    @GameTest
    public void fullInventoryRoundTripConservesEveryItem(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel dungeon = enterDungeonDimension(helper, server);
        ServerPlayer player = standInALoadedChunk(helper);
        Haunts haunts = new Haunts(player);

        fillEverySlot(player);
        player.containerMenu.setCarried(new ItemStack(Items.NETHERITE_INGOT, 5));
        Map<Item, Integer> before = custodyTotals(server, player, haunts);

        teleport(helper, player, dungeon, haunts, "into the dungeon");
        teleport(helper, player, server.overworld(), haunts, "back out of the dungeon");

        helper.assertFalse(InventorySwap.Probe.isStashed(player),
                "the stash flag cleared on the way out");

        settleThenAssert(helper, () -> {
            assertConserved(helper, before, custodyTotals(server, player, haunts),
                    "the round trip conserved every survival item");
            cleanUp(server, player);
        });
    }

    /**
     * A dungeon inventory larger than the pack it is restored into.
     *
     * <p>The kept record fills all 36 main slots and carries a cursor stack
     * and loose stacks as well. Everything that has a slot goes back into that
     * slot; the rest stays in the record for the next entry. Nothing may be
     * dropped (the ground is a dungeon) and nothing may be voided.
     */
    @GameTest
    public void dungeonInventoryOverflowIsKeptNotDropped(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = standInALoadedChunk(helper);
        DungeonLog log = DungeonLog.forServer(server);

        List<ItemStack> kept = new ArrayList<>();
        for (int i = 0; i < InventorySwap.SLOTS; i++) {
            kept.add(ItemStack.EMPTY);
        }
        for (int i = 0; i < InventorySwap.MAIN_COUNT; i++) {
            kept.set(i, new ItemStack(Items.GOLD_INGOT, 1));
        }
        kept.set(7, new ItemStack(Items.DIAMOND_SWORD));
        kept.set(InventorySwap.ARMOR_START + 2, new ItemStack(Items.IRON_CHESTPLATE));
        kept.set(InventorySwap.OFFHAND, new ItemStack(Items.SHIELD));
        kept.set(InventorySwap.CURSOR, new ItemStack(Items.EMERALD, 3));
        kept.add(new ItemStack(Items.DIAMOND, 2));
        kept.add(new ItemStack(Items.DIAMOND, 5));
        log.setOrphan(player.getUUID(), new InventorySwap.OrphanRecord(kept));
        emptyInventory(player);
        Haunts haunts = new Haunts(player);
        Map<Item, Integer> before = custodyTotals(server, player, haunts);

        // The entry happens where the player already stands, by pointing the
        // invariant at this level rather than teleporting into the nether.
        // reconcile's entering branch is the same code either way; the
        // structure's own chunk is simply the one place a stray drop would be
        // visible to the count below.
        helper.runAfterDelay(4L, () -> {
            InventorySwap.Probe.useDimensionForTesting(Level.OVERWORLD);
            InventorySwap.Probe.forceStash(player, false, List.of());
            InventorySwap.Probe.reconcileNow(player);
            InventorySwap.Probe.useDimensionForTesting(Level.NETHER);
            helper.assertTrue(InventorySwap.Probe.isStashed(player), "the entering branch ran");
            helper.assertTrue(player.getInventory().getItem(7).is(Items.DIAMOND_SWORD),
                    "the sword came back to hotbar slot 7");
            helper.assertTrue(player.getInventory().getItem(InventorySwap.ARMOR_START + 2).is(Items.IRON_CHESTPLATE),
                    "the chestplate came back to the chest slot");
            helper.assertTrue(player.getInventory().getItem(InventorySwap.OFFHAND).is(Items.SHIELD),
                    "the shield came back to the offhand");
            helper.assertValueEqual(log.orphanOf(player.getUUID()).stackCount(), 3,
                    "the cursor stack and both loose stacks wait in the record");
        });
        helper.runAfterDelay(9L, () -> {
            helper.assertValueEqual(droppedNear(server, haunts), 0, "nothing was dropped");
            assertConserved(helper, before, custodyTotals(server, player, haunts),
                    "every kept item is in a slot or still in the record, none voided");
            cleanUp(server, player);
            helper.succeed();
        });
    }

    /**
     * Leave then re-enter with a dungeon inventory: it is kept whole, slot for
     * slot, and nothing is delivered anywhere else.
     *
     * <p>This is the no-auto-delivery property. The void inventory used to be
     * emptied into the room's chests on the way out; now the record is its
     * only destination, so after the leave every item is in the record and in
     * no slot, container or item entity.
     */
    @GameTest
    public void dungeonInventoryRoundTripKeepsEverySlot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel dungeon = enterDungeonDimension(helper, server);
        ServerPlayer player = standInALoadedChunk(helper);
        Haunts haunts = new Haunts(player);
        DungeonLog log = DungeonLog.forServer(server);

        player.getInventory().setItem(4, new ItemStack(Items.APPLE, 3));
        Map<Item, Integer> survival = custodyTotals(server, player, haunts);
        teleport(helper, player, dungeon, haunts, "into the dungeon");

        player.getInventory().setItem(3, new ItemStack(Items.STONE_PICKAXE));
        player.getInventory().setItem(20, new ItemStack(Items.COBBLESTONE, 16));
        player.getInventory().setItem(InventorySwap.ARMOR_START + 2, new ItemStack(Items.LEATHER_CHESTPLATE));
        player.getInventory().setItem(InventorySwap.OFFHAND, new ItemStack(Items.TORCH, 4));
        Map<Item, Integer> pack = new HashMap<>();
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            add(pack, player.getInventory().getItem(slot));
        }

        teleport(helper, player, server.overworld(), haunts, "out of the dungeon");
        List<ItemStack> record = log.orphanOf(player.getUUID()).items();
        helper.assertTrue(record.size() >= InventorySwap.SLOTS, "the record is a positional snapshot");
        helper.assertTrue(record.get(3).is(Items.STONE_PICKAXE), "the pickaxe is kept in slot 3");
        helper.assertTrue(record.get(InventorySwap.ARMOR_START + 2).is(Items.LEATHER_CHESTPLATE),
                "the chestplate is kept in the chest slot");
        assertConserved(helper, pack, totalsOf(record), "the record holds the whole pack and nothing else");

        teleport(helper, player, dungeon, haunts, "back into the dungeon");
        helper.assertTrue(player.getInventory().getItem(3).is(Items.STONE_PICKAXE), "slot 3 again");
        helper.assertTrue(player.getInventory().getItem(20).is(Items.COBBLESTONE), "slot 20 again");
        helper.assertTrue(player.getInventory().getItem(InventorySwap.OFFHAND).is(Items.TORCH), "offhand again");
        helper.assertTrue(log.orphanOf(player.getUUID()).items().isEmpty(),
                "a record that fully restored is cleared, so the next entry cannot duplicate it");

        teleport(helper, player, server.overworld(), haunts, "out for good");
        settleThenAssert(helper, () -> {
            Map<Item, Integer> all = new HashMap<>(survival);
            pack.forEach((item, count) -> all.merge(item, count, Integer::sum));
            assertConserved(helper, all, custodyTotals(server, player, haunts),
                    "survival and the pack both survived two round trips");
            cleanUp(server, player);
        });
    }

    /**
     * Entering and leaving never grant items, and the one migration grant is
     * exactly one.
     *
     * <p>Before the grant-once rule, every entry with an empty orphan applied
     * the bag kit again, and the leave delivered the pack to the room's chests
     * and cleared the orphan, so walking in and out stocked the room with a
     * fresh kit each time. Here a Lumberjack who has never been granted under
     * the new rule gets one kit on the first entry and nothing on the next
     * five round trips; the per-item totals across every custody place stay
     * put. L1 (D40): the kit is Lumberjack's, since Mason moved to the
     * extra_bags module.
     */
    @GameTest
    public void repeatedEnterAndLeaveGrantsNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = standInALoadedChunk(helper);
        DungeonLog log = DungeonLog.forServer(server);
        helper.assertTrue(Bags.byId("pocketdungeons:lumberjack") != null,
                "the Lumberjack bag is loaded");
        log.setBag(player.getUUID(), "pocketdungeons:lumberjack");
        emptyInventory(player);
        Haunts haunts = new Haunts(player);

        helper.runAfterDelay(4L, () -> {
            // First entry: the one migration grant.
            cycle(player, true);
            helper.assertTrue(log.get(player.getUUID()).kitGranted(), "the migration grant is recorded");
            Map<Item, Integer> afterGrant = custodyTotals(server, player, haunts);
            helper.assertValueEqual(afterGrant.getOrDefault(Items.OAK_LOG, 0), 8,
                    "the Lumberjack kit arrived once: eight oak logs");
            helper.assertValueEqual(afterGrant.getOrDefault(Items.STONE_AXE, 0), 1, "and one axe");

            for (int round = 0; round < 5; round++) {
                cycle(player, false);
                cycle(player, true);
                assertConserved(helper, afterGrant, custodyTotals(server, player, haunts),
                        "round " + round + " granted nothing");
            }
            cycle(player, false);
            assertConserved(helper, afterGrant, custodyTotals(server, player, haunts),
                    "the final leave granted nothing");
            InventorySwap.Probe.useDimensionForTesting(Level.NETHER);
            cleanUp(server, player);
            helper.succeed();
        });
    }

    /**
     * One swap for a player standing in the overworld: an entry when the
     * invariant is pointed at the overworld, a leave when it is pointed back at
     * the nether. Both land in the same tick as the call, so a sibling
     * scenario cannot move the shared field in between.
     */
    private static void cycle(ServerPlayer player, boolean enter) {
        InventorySwap.Probe.useDimensionForTesting(enter ? Level.OVERWORLD : Level.NETHER);
        InventorySwap.Probe.reconcileNow(player);
    }

    /**
     * The leave journal repairs a dungeon inventory the saved data lost, and
     * leaves an intact one alone.
     *
     * <p>Staged the same way as the stash repair below: the record is written,
     * the live inventory cleared, and the saved data's copy of the record is
     * lost before it reaches disk.
     */
    @GameTest
    public void aLostDungeonInventoryIsRepairedFromTheJournal(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = standInALoadedChunk(helper);
        DungeonLog log = DungeonLog.forServer(server);

        helper.runAfterDelay(4L, () -> {
            InventorySwap.Probe.useDimensionForTesting(Level.NETHER);
            List<ItemStack> kept = new ArrayList<>();
            for (int i = 0; i < InventorySwap.SLOTS; i++) {
                kept.add(ItemStack.EMPTY);
            }
            kept.set(2, new ItemStack(Items.STONE_PICKAXE));
            kept.set(9, new ItemStack(Items.COBBLESTONE, 12));
            Map<Item, Integer> pack = totalsOf(kept);

            long op = InventoryJournal.prepareLeaving(server, player, kept);
            helper.assertFalse(op == 0L, "the leave was journalled");
            log.setStash(player.getUUID(), InventorySwap.StashRecord.NONE);
            log.setOrphan(player.getUUID(), InventorySwap.OrphanRecord.NONE);

            InventoryJournal.forgetCheckedForTesting();
            InventorySwap.Probe.reconcileNow(player);
            List<ItemStack> repaired = log.orphanOf(player.getUUID()).items();
            assertConserved(helper, pack, totalsOf(repaired), "the repaired record holds the pack");
            helper.assertTrue(repaired.get(2).is(Items.STONE_PICKAXE), "in its slots");
            helper.assertFalse(java.nio.file.Files.exists(
                    InventoryJournal.leavingFileForTesting(server, player.getUUID())), "and the record is retired");

            // A stale record next to an intact dungeon inventory is retired,
            // never applied on top of it.
            InventoryJournal.prepareLeaving(server, player, List.of(new ItemStack(Items.DIAMOND, 64)));
            InventoryJournal.forgetCheckedForTesting();
            InventorySwap.Probe.reconcileNow(player);
            assertConserved(helper, pack, totalsOf(log.orphanOf(player.getUUID()).items()),
                    "an intact record was left alone");
            helper.assertFalse(java.nio.file.Files.exists(
                    InventoryJournal.leavingFileForTesting(server, player.getUUID())), "the stale record is gone");

            cleanUp(server, player);
            helper.succeed();
        });
    }

    /**
     * The M63 journal repairs a stash record    /**
     * The M63 journal repairs a stash record the saved data lost, and does not
     * invent one when the stash is intact.
     *
     * <p>The fault injected is the real one: {@code SavedDataStorage} writes
     * {@code dungeon_log.dat} with a bare {@code NbtIo.writeCompressed} onto
     * the live path, so a kill part way through leaves the stash record gone
     * while the player's inventory has already been cleared. That is staged
     * here by clearing the record directly, which is the state the truncated
     * write produces, and then asking the reconciliation pass to cope.
     */
    @GameTest
    public void aLostStashRecordIsRepairedFromTheJournal(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = standInALoadedChunk(helper);
        DungeonLog log = DungeonLog.forServer(server);

        helper.runAfterDelay(4L, () -> {
            InventorySwap.Probe.useDimensionForTesting(Level.OVERWORLD);
            fillEverySlot(player);
            List<ItemStack> snapshot = new ArrayList<>();
            Map<Item, Integer> survival = new HashMap<>();
            for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
                ItemStack stack = player.getInventory().getItem(slot).copy();
                snapshot.add(stack);
                add(survival, stack);
            }

            // The crash window itself, staged rather than simulated after the
            // fact: the journal record is written, the live inventory is
            // cleared, and then the process dies before SavedDataStorage's
            // non-atomic write of the stash record reaches disk. Driving a full
            // entry and then deleting the record would prove nothing, because a
            // completed entry retires its own journal record on the way out.
            long op = InventoryJournal.prepare(server, player, snapshot);
            helper.assertFalse(op == 0L, "the hand-off was journalled");
            emptyInventory(player);
            log.setStash(player.getUUID(), InventorySwap.StashRecord.NONE);

            // The player logs back in and the reconciliation pass runs.
            InventoryJournal.forgetCheckedForTesting();
            InventorySwap.Probe.reconcileNow(player);

            helper.assertTrue(InventorySwap.Probe.isStashed(player),
                    "the repaired player is stashed again rather than being re-stashed empty");
            Map<Item, Integer> recovered = new HashMap<>();
            for (ItemStack stack : InventorySwap.Probe.backupOf(player)) {
                add(recovered, stack);
            }
            assertConserved(helper, survival, recovered,
                    "the repaired stash holds exactly the survival inventory that was taken");

            // Idempotence: replaying the repair must not double anything, and
            // must not re-stash the player's now-empty live inventory over the
            // record it just restored.
            InventoryJournal.forgetCheckedForTesting();
            InventorySwap.Probe.reconcileNow(player);
            Map<Item, Integer> again = new HashMap<>();
            for (ItemStack stack : InventorySwap.Probe.backupOf(player)) {
                add(again, stack);
            }
            assertConserved(helper, survival, again, "a replayed repair duplicated nothing");

            cleanUp(server, player);
            helper.succeed();
        });
    }

    // ---- helpers ------------------------------------------------------------

    /** Points the invariant at the nether and hands back that level. */
    private static ServerLevel enterDungeonDimension(GameTestHelper helper, MinecraftServer server) {
        ServerLevel dungeon = server.getLevel(Level.NETHER);
        if (dungeon == null) {
            helper.fail("the stand-in dungeon dimension is not loaded on this server");
        }
        InventorySwap.Probe.useDimensionForTesting(Level.NETHER);
        return dungeon;
    }

    /**
     * A mock player standing somewhere its dropped items will actually exist.
     *
     * <p>{@code makeMockServerPlayerInLevel} leaves the player at the world
     * origin, which is not a loaded chunk in a gametest world. Verified in the
     * 26.2 jar: {@code LivingEntity.drop} ends in
     * {@code Level.addFreshEntity}, which discards an entity destined for an
     * unloaded chunk and returns false rather than throwing. A conservation
     * assertion run against a player standing there scores every legitimate
     * drop as a voided item, which is a false positive of exactly the failure
     * this milestone is hunting. The structure the harness force-loads is the
     * one place a drop is guaranteed to survive.
     */
    private static ServerPlayer standInALoadedChunk(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // Survival, not the creative a mock player is created in: verified in
        // the 26.2 bytecode, Inventory.add has a Player.hasInfiniteMaterials
        // branch that empties an overflowing stack and reports success, so a
        // creative player cannot distinguish a delivered item from a voided one.
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }

    /**
     * Teleports, having first force-loaded the destination chunk for the same
     * reason {@link #standInALoadedChunk} exists: an item dropped into an
     * unloaded chunk is discarded, and this scenario's whole question is
     * whether items survive.
     */
    /**
     * Teleports to this scenario's own private coordinates in {@code target}.
     *
     * <p>Two things are load bearing here. The destination chunk is force
     * loaded, for the reason {@link #standInALoadedChunk} gives: an item
     * dropped into an unloaded chunk is discarded rather than dropped, and
     * whether items survive is the entire question. And the destination is
     * derived from the calling scenario's own structure position rather than
     * being a fixed spot, because gametests run concurrently against one
     * server: two scenarios teleporting to the same coordinates land in each
     * other's item sweeps, and a conservation count that picks up a neighbour's
     * dropped stacks reports items appearing from nowhere.
     */
    private static void teleport(GameTestHelper helper, ServerPlayer player, ServerLevel target,
                                 Haunts haunts, String what) {
        haunts.remember(player);
        BlockPos here = helper.absolutePos(BlockPos.ZERO);
        double x = here.getX() + 0.5;
        double z = here.getZ() + 0.5;
        target.setChunkForced(here.getX() >> 4, here.getZ() >> 4, true);
        // getChunk, not just the forced ticket. A gametest structure sits
        // millions of blocks out, so the matching chunk in the other dimension
        // has never been generated; a ticket only schedules that work, and a
        // few ticks is not enough for it to finish. getChunk blocks until the
        // chunk actually exists, which is what an addFreshEntity into it needs.
        target.getChunk(here.getX() >> 4, here.getZ() >> 4);
        boolean moved = player.teleportTo(target, x, 80.0, z, Set.of(), 0.0F, 0.0F, false);
        helper.assertTrue(moved, "the teleport " + what + " took");
        haunts.remember(player);
        // Re-point the invariant immediately before the pass that reads it.
        // useDimensionForTesting writes one static field, gametests run
        // concurrently against one server, and a sibling scenario finishing
        // mid-flight resets that field to pocketdungeons:void. A reconcile that
        // straddles a sibling's cleanup would otherwise decide this player is
        // not in the dungeon at all and do nothing, which looks like the swap
        // silently declining to run.
        InventorySwap.Probe.useDimensionForTesting(Level.NETHER);
        InventorySwap.Probe.reconcileNow(player);
    }

    /**
     * Runs the assertions a tick later rather than in the same tick as the
     * transition.
     *
     * <p>Not a sleep dressed up: {@code ServerLevel.addFreshEntity} does not
     * make an entity queryable through {@code getEntitiesOfClass} until the
     * level's entity manager has processed the addition, which happens on the
     * next tick. A conservation count taken in the same tick as a drop
     * therefore reports the dropped stack as missing, which reads exactly like
     * the item having been voided. Every scenario here that can drop something
     * settles one tick first, so the difference between "dropped" and "lost"
     * is one the assertion can actually see.
     */
    private static void settleThenAssert(GameTestHelper helper, Runnable assertions) {
        helper.runAfterDelay(2L, () -> {
            assertions.run();
            helper.succeed();
        });
    }

    /**
     * Every place the player has stood, so a dropped stack left behind at a
     * previous position is still counted.
     *
     * <p>This matters because of what the existing
     * {@code InventorySwapGameTest.cursorItemSurvivesAReconcile} documents: on
     * a teleport, vanilla disposes of the carried stack through
     * {@code AbstractContainerMenu.removed} before any Fabric event fires, and
     * with a full inventory that means dropping it at the <em>origin</em>
     * dimension and position. Sweeping only where the player ended up would
     * score that vanilla drop as a loss this mod caused.
     */
    private static final class Haunts {
        private final List<AABB> boxes = new ArrayList<>();

        Haunts(ServerPlayer player) {
            remember(player);
        }

        void remember(ServerPlayer player) {
            boxes.add(player.getBoundingBox().inflate(5.0));
        }

        List<AABB> boxes() {
            return boxes;
        }
    }

    /**
     * Every item this player is accountable for, wherever it currently lives.
     *
     * <p>The five places are the whole of the custody surface: the live slots,
     * the cursor, the stash record holding a survival inventory, the dungeon
     * inventory record holding a void inventory, and the floor at any position the player
     * has occupied, once something has been dropped rather than voided.
     */
    private static Map<Item, Integer> custodyTotals(MinecraftServer server, ServerPlayer player,
                                                    Haunts haunts) {
        Map<Item, Integer> totals = new HashMap<>();
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            add(totals, player.getInventory().getItem(slot));
        }
        add(totals, player.containerMenu.getCarried());
        DungeonLog log = DungeonLog.forServer(server);
        for (ItemStack stack : log.stashOf(player.getUUID()).backup()) {
            add(totals, stack);
        }
        for (ItemStack stack : log.orphanOf(player.getUUID()).items()) {
            add(totals, stack);
        }
        haunts.remember(player);
        Set<ItemEntity> counted = new java.util.HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (AABB box : haunts.boxes()) {
                counted.addAll(level.getEntitiesOfClass(ItemEntity.class, box));
            }
        }
        for (ItemEntity dropped : counted) {
            add(totals, dropped.getItem());
        }
        return totals;
    }

    /** How many item entities lie at any position the player has occupied. */
    private static int droppedNear(MinecraftServer server, Haunts haunts) {
        Set<ItemEntity> found = new java.util.HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (AABB box : haunts.boxes()) {
                found.addAll(level.getEntitiesOfClass(ItemEntity.class, box));
            }
        }
        return found.size();
    }

    private static Map<Item, Integer> totalsOf(List<ItemStack> stacks) {
        Map<Item, Integer> totals = new HashMap<>();
        for (ItemStack stack : stacks) {
            add(totals, stack);
        }
        return totals;
    }

    private static void add(Map<Item, Integer> totals, ItemStack stack) {
        if (!stack.isEmpty()) {
            totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    /** Exact per-item equality in both directions: nothing lost, nothing invented. */
    private static void assertConserved(GameTestHelper helper, Map<Item, Integer> before,
                                        Map<Item, Integer> after, String what) {
        for (Map.Entry<Item, Integer> entry : before.entrySet()) {
            int found = after.getOrDefault(entry.getKey(), 0);
            if (found != entry.getValue()) {
                helper.fail(what + ": expected " + entry.getValue() + " " + entry.getKey()
                        + " but found " + found);
            }
        }
        for (Map.Entry<Item, Integer> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                helper.fail(what + ": " + entry.getValue() + " " + entry.getKey()
                        + " appeared from nowhere");
            }
        }
    }

    /** Every one of the 41 addressable slots occupied, so nothing has room to spare. */
    private static void fillEverySlot(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.getInventory().setItem(36, new ItemStack(Items.NETHERITE_BOOTS));
        player.getInventory().setItem(37, new ItemStack(Items.DIAMOND_LEGGINGS));
        player.getInventory().setItem(38, new ItemStack(Items.IRON_CHESTPLATE));
        player.getInventory().setItem(39, new ItemStack(Items.TURTLE_HELMET));
        player.getInventory().setItem(40, new ItemStack(Items.SHIELD));
    }

    private static void emptyInventory(ServerPlayer player) {
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    /**
     * Leaves the server as the scenario found it. A stash or an orphan left
     * behind would be swept into the next scenario by its own reconcile pass.
     */
    private static void cleanUp(MinecraftServer server, ServerPlayer player) {
        DungeonLog log = DungeonLog.forServer(server);
        log.setStash(player.getUUID(), InventorySwap.StashRecord.NONE);
        log.setOrphan(player.getUUID(), InventorySwap.OrphanRecord.NONE);
        log.resetCampaign(player.getUUID());
        emptyInventory(player);
        server.getPlayerList().remove(player);
        // Deliberately not resetting useDimensionForTesting here. It is one
        // static field shared by every concurrently running scenario, and
        // putting it back mid-run is what breaks a sibling still in flight.
        // Nothing outside this harness reads it, and the gametest server exits
        // when the run ends.
    }
}
