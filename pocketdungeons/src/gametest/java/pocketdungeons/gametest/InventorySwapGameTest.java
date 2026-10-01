package pocketdungeons.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import pocketdungeons.InventorySwap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The five scenarios SITUATIONS_SPEC section 10 step 1 asks for, plus the PD-92
 * max-omen revert, run against a
 * real dedicated server with a real {@code ServerPlayer}.
 *
 * <p>No Carpet. {@code GameTestHelper.makeMockServerPlayerInLevel} builds a
 * player through {@code PlayerList.placeNewPlayer}, the same path a real login
 * takes, and DISCOVERIES entry 18 records the probe run that proved it carries
 * cross-dimension teleport, respawn and disconnect. Two catches from that same
 * entry apply here: the helper is {@code @Deprecated(forRemoval = true)} in
 * 26.2 with no supported replacement, hence the suppression on this class, and
 * {@code player.die} does not zero health, hence the explicit
 * {@code setHealth(0)} in the respawn scenario.
 *
 * <p>Every scenario drives the reconciliation pass explicitly through
 * {@code InventorySwap.Probe} rather than waiting on the end-of-tick hook.
 * That is not a shortcut around the production path: {@code Probe.reconcileNow}
 * is the same method the tick hook, the join hook and the dimension-change hook
 * all call. Driving it synchronously makes each test deterministic and lets it
 * assert the state between two passes, which is what the deduplication
 * scenarios need.
 */
@SuppressWarnings("removal")
public final class InventorySwapGameTest {

    /** Scenario 1: entering the void stashes the survival inventory. */
    @GameTest
    public void entryStashesSurvival(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel dungeon = requireDungeon(helper, server);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        fillSurvival(player);
        List<ItemStack> before = liveSlots(player);
        helper.assertFalse(InventorySwap.Probe.isStashed(player), "nothing is stashed before entry");

        intoVoid(helper, player, dungeon);

        helper.assertTrue(InventorySwap.Probe.isStashed(player), "entry set the stash flag");
        List<ItemStack> backup = InventorySwap.Probe.backupOf(player);
        helper.assertValueEqual(backup.size(), InventorySwap.Probe.SLOTS,
                "the backup is a full 42 slot snapshot");
        helper.assertTrue(sameContents(before, backup.subList(0, before.size())),
                "the backup holds the survival inventory");
        helper.assertTrue(isEmptyInventory(player),
                "the live inventory is empty in the dungeon (no keystone at level 0)");

        // The dimension-change event and the tick pass both land here. A second
        // pass must not stash again over the top of the first backup.
        InventorySwap.Probe.reconcileNow(player);
        helper.assertTrue(sameContents(backup, InventorySwap.Probe.backupOf(player)),
                "a second reconcile left the backup alone");

        cleanUp(server, player);
        helper.succeed();
    }

    /**
     * Scenario 2: leaving the void restores the survival inventory exactly,
     * cursor included.
     *
     * <p>The expectation accounts for something the spec does not: on a
     * teleport, vanilla has already dealt with the carried stack before any
     * Fabric event fires. See {@link #cursorItemSurvivesAReconcile} for the
     * detail and for the path this mod does own. Either way the stack ends up
     * in the first free main slot, so that is what the round trip has to
     * reproduce.
     */
    @GameTest
    public void exitRestoresSurvivalExactly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel dungeon = requireDungeon(helper, server);
        ServerLevel overworld = server.overworld();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        fillSurvival(player);
        player.containerMenu.setCarried(new ItemStack(Items.TOTEM_OF_UNDYING));

        // What the survival inventory must look like once the carried stack has
        // been folded back into it: unchanged, plus the totem in the first free
        // main slot.
        List<ItemStack> expected = liveSlots(player);
        expected.set(firstFreeMainSlot(expected), new ItemStack(Items.TOTEM_OF_UNDYING));

        intoVoid(helper, player, dungeon);
        helper.assertTrue(InventorySwap.Probe.isStashed(player), "the player is stashed inside");
        helper.assertTrue(player.containerMenu.getCarried().isEmpty(),
                "nothing is left carried inside the dungeon");

        // Something picked up inside the dungeon, which must not follow them out.
        player.getInventory().setItem(5, new ItemStack(Items.GOLD_INGOT, 7));

        outOfVoid(helper, player, overworld);

        helper.assertFalse(InventorySwap.Probe.isStashed(player), "the flag cleared on the way out");
        List<ItemStack> after = liveSlots(player);
        helper.assertTrue(sameContents(expected, after), "every survival slot came back unchanged");
        helper.assertValueEqual(countOf(after, Items.GOLD_INGOT), 0,
                "the dungeon loot did not follow them out");
        helper.assertValueEqual(countOf(after, Items.TOTEM_OF_UNDYING), 1,
                "the cursor stack came back exactly once");

        cleanUp(server, player);
        helper.succeed();
    }

    /**
     * Scenario 3, the issue #22 sequence: enter, disconnect, reconnect, be
     * teleported out by something that is not this mod, and have survival
     * restored on the next tick.
     *
     * <p>The teleport-out is modelled the only honest way it can be: the state
     * the missing event would have prevented is built directly, and the tick
     * sweep is asked to fix it. That is exactly the layer under test. A
     * teleport driven through {@code teleportTo} would fire
     * {@code AFTER_PLAYER_CHANGE_LEVEL} and test layer 1 instead, which
     * scenario 2 already covers.
     */
    @GameTest
    public void issue22LogoutLoginTeleportRestores(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel dungeon = requireDungeon(helper, server);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        fillSurvival(player);
        List<ItemStack> survival = liveSlots(player);
        intoVoid(helper, player, dungeon);
        List<ItemStack> backup = new ArrayList<>(InventorySwap.Probe.backupOf(player));

        // Logout, with the stash held.
        server.getPlayerList().remove(player);
        helper.assertTrue(sameContents(backup, InventorySwap.Probe.backupOf(player)),
                "the stash survived the disconnect");

        // Login, then something else moves them out of the dungeon without the
        // dimension-change event ever reaching us.
        ServerPlayer back = helper.makeMockServerPlayerInLevel();
        InventorySwap.Probe.forceStash(back, true, backup);
        emptyInventory(back);
        back.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
        helper.assertFalse(back.level().dimension().equals(Level.NETHER),
                "the player is outside the dungeon and still flagged as stashed");

        InventorySwap.Probe.reconcileAllNow(server);

        helper.assertFalse(InventorySwap.Probe.isStashed(back), "the tick sweep cleared the flag");
        helper.assertTrue(sameContents(survival, liveSlots(back)),
                "survival was restored without any event firing");

        server.getPlayerList().remove(player);
        cleanUp(server, back);
        helper.succeed();
    }

    /**
     * Scenario 4, the issue #25 sequence: killed inside the void, respawned
     * outside it. Survival is restored, and restored once.
     */
    @GameTest
    public void issue25RespawnRestoresWithoutDuplicating(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel dungeon = requireDungeon(helper, server);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        fillSurvival(player);
        int swords = countOf(liveSlots(player), Items.DIAMOND_SWORD);
        int beef = countOf(liveSlots(player), Items.COOKED_BEEF);
        List<ItemStack> survival = liveSlots(player);

        intoVoid(helper, player, dungeon);
        helper.assertTrue(InventorySwap.Probe.isStashed(player), "stashed inside the dungeon");

        // DISCOVERIES entry 18: die() alone leaves isDeadOrDying() false,
        // because it does not zero the health bar.
        player.setHealth(0.0F);
        player.die(player.damageSources().fellOutOfWorld());
        ServerPlayer respawned = server.getPlayerList()
                .respawn(player, false, Entity.RemovalReason.KILLED);

        helper.assertFalse(respawned.level().dimension().equals(Level.NETHER),
                "the respawn put them outside the dungeon");

        // Both events that can fire on a cross-dimension respawn, plus the tick
        // sweep. This is the doubled fire that duplicated items in issue #25.
        InventorySwap.Probe.reconcileNow(respawned);
        InventorySwap.Probe.reconcileNow(respawned);
        InventorySwap.Probe.reconcileAllNow(server);

        helper.assertFalse(InventorySwap.Probe.isStashed(respawned), "the flag cleared once");
        List<ItemStack> after = liveSlots(respawned);
        helper.assertTrue(sameContents(survival, after), "survival came back exactly as it was");
        helper.assertValueEqual(countOf(after, Items.DIAMOND_SWORD), swords,
                "the sword was not duplicated");
        helper.assertValueEqual(countOf(after, Items.COOKED_BEEF), beef,
                "and neither was the food");

        cleanUp(server, respawned);
        helper.succeed();
    }

    /**
     * Scenario 5, vanilla MC-258705: a stack held on the mouse cursor when the
     * swap runs.
     *
     * <p>This is the scenario that corrected the spec. Spec 11.2 and 11.6 say
     * calling {@code closeContainer} before the snapshot fixes the cursor loss
     * on a dimension change. Verified against the 26.2 jar and then against a
     * running server, that is not the whole story on the teleport path:
     * {@code ServerPlayer.teleport} calls
     * {@code ServerLevel.removePlayerImmediately}, which disposes of the
     * carried stack through {@code AbstractContainerMenu.removed} <em>before</em>
     * {@code AFTER_PLAYER_CHANGE_LEVEL} fires. By the time this mod is told the
     * player changed dimension, vanilla has already put the stack in a free
     * inventory slot, or dropped it at the origin if there was no free slot.
     * Fabric ships no BEFORE variant of that event (checked in
     * fabric-entity-events-v1 5.0.5: {@code AfterEntityChange} and
     * {@code AfterPlayerChange} are the only two), so getting ahead of it would
     * take a mixin, and this mod's mixin budget is spent.
     *
     * <p>What this mod does own is every reconcile that is <em>not</em>
     * preceded by a vanilla teleport: the tick sweep and the join handler. That
     * is the issue #22 path, and it is the one under test here. The cursor
     * stack is captured into slot 41 of the backup rather than dropped, which
     * is what no other per-dimension inventory mod in the spec's research does.
     */
    @GameTest
    public void cursorItemSurvivesAReconcile(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel dungeon = requireDungeon(helper, server);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        // Get the player inside with the swap already settled, then put the
        // state back to "owes a stash" so the entering branch runs on a player
        // who is standing still, with no teleport in the way.
        intoVoid(helper, player, dungeon);
        InventorySwap.Probe.forceStash(player, false, List.of());

        // A full main inventory, so there is nowhere for a carried stack to go
        // except slot 41 of the snapshot.
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.containerMenu.setCarried(new ItemStack(Items.NETHERITE_INGOT, 3));

        InventorySwap.Probe.reconcileNow(player);

        List<ItemStack> backup = InventorySwap.Probe.backupOf(player);
        helper.assertValueEqual(backup.size(), InventorySwap.Probe.SLOTS,
                "the snapshot is 42 slots, not 41");
        ItemStack cursor = backup.get(InventorySwap.Probe.SLOTS - 1);
        helper.assertTrue(cursor.is(Items.NETHERITE_INGOT),
                "the cursor stack is in slot 41 of the backup, found " + cursor);
        helper.assertValueEqual(cursor.getCount(), 3, "with its full count");
        helper.assertTrue(player.containerMenu.getCarried().isEmpty(),
                "and nothing is left on the cursor");
        helper.assertValueEqual(countOf(backup.subList(0, 36), Items.COBBLESTONE), 36 * 64,
                "the rest of the inventory went into the backup alongside it");

        cleanUp(server, player);
        helper.succeed();
    }

    /**
     * PD-92: a max-omen ejection reverts the pack to the interval-start
     * snapshot, sends the player home, and the next entry hands back every
     * stack exactly once.
     *
     * <p>The snapshot fills the main inventory, so its cursor stack has to go
     * loose, and the record already holds a loose stack from before the
     * failure, which the revert must not wipe. Loot picked up during the
     * interval must not survive it.
     */
    @GameTest
    public void maxOmenEjectionKeepsEachStackOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel dungeon = requireDungeon(helper, server);
        ServerLevel overworld = server.overworld();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        emptyInventory(player);
        intoVoid(helper, player, dungeon);

        List<ItemStack> snapshot = new ArrayList<>();
        for (int slot = 0; slot < InventorySwap.Probe.SLOTS; slot++) {
            snapshot.add(slot < 36 ? new ItemStack(Items.COBBLESTONE, 64) : ItemStack.EMPTY);
        }
        snapshot.set(0, new ItemStack(Items.DIAMOND_SWORD));
        snapshot.set(8, new ItemStack(Items.COOKED_BEEF, 32));
        snapshot.set(36, new ItemStack(Items.NETHERITE_BOOTS));
        snapshot.set(InventorySwap.Probe.SLOTS - 1, new ItemStack(Items.NETHERITE_INGOT, 3));

        // The interval's loot, and a top-up that did not fit earlier.
        player.getInventory().setItem(5, new ItemStack(Items.GOLD_INGOT, 7));
        InventorySwap.Probe.keepLoose(player, List.of(new ItemStack(Items.EMERALD, 5)));

        InventorySwap.Probe.restoreIntervalSnapshotNow(player, snapshot);
        outOfVoid(helper, player, overworld);
        helper.assertFalse(InventorySwap.Probe.isStashed(player), "the ejection restored survival");
        intoVoid(helper, player, dungeon);

        List<ItemStack> held = liveSlots(player);
        held.addAll(InventorySwap.Probe.keptOf(player));
        helper.assertValueEqual(countOf(held, Items.DIAMOND_SWORD), 1, "the sword exists once");
        helper.assertValueEqual(countOf(held, Items.COOKED_BEEF), 32, "the food exists once");
        helper.assertValueEqual(countOf(held, Items.NETHERITE_BOOTS), 1, "the boots exist once");
        helper.assertValueEqual(countOf(held, Items.COBBLESTONE), 34 * 64, "the filler exists once");
        helper.assertValueEqual(countOf(held, Items.NETHERITE_INGOT), 3,
                "the cursor stack went loose and exists once");
        helper.assertValueEqual(countOf(held, Items.EMERALD), 5, "the earlier loose stack survived");
        helper.assertValueEqual(countOf(held, Items.GOLD_INGOT), 0, "the interval's loot was reverted");

        InventorySwap.Probe.clearKept(player);
        cleanUp(server, player);
        helper.succeed();
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * The dimension standing in for {@code pocketdungeons:void}.
     *
     * <p>Verified in the 26.2 jar: {@code GameTestServer.create} bakes an empty
     * {@code LEVEL_STEM} registry against the flat world preset, so a gametest
     * server has the overworld, the nether and the end and nothing else. A
     * datapack dimension is never created, and creating one would take a mixin
     * into world creation that this mod has no budget for. So the invariant is
     * pointed at the nether for the duration of each scenario and everything
     * else runs for real. The identity of the dimension is one {@code equals}
     * call and is on the live checklist instead.
     */
    private static ServerLevel requireDungeon(GameTestHelper helper, MinecraftServer server) {
        ServerLevel dungeon = server.getLevel(Level.NETHER);
        if (dungeon == null) {
            helper.fail("the stand-in dungeon dimension is not loaded on this server");
        }
        InventorySwap.Probe.useDimensionForTesting(Level.NETHER);
        return dungeon;
    }

    /**
     * Puts the player in the dungeon dimension and makes sure the swap has run.
     *
     * <p>The teleport fires {@code AFTER_PLAYER_CHANGE_LEVEL}, so the swap has
     * usually already happened by the time this returns; the explicit pass
     * after it is both a belt-and-braces for the event not firing on a mock
     * player and, in the entry scenario, the second call whose no-op is the
     * thing being asserted.
     */
    private static void intoVoid(GameTestHelper helper, ServerPlayer player, ServerLevel dungeon) {
        boolean moved = player.teleportTo(dungeon, 0.5, 80.0, 0.5, Set.of(), 0.0F, 0.0F, false);
        helper.assertTrue(moved, "the teleport into pocketdungeons:void took");
        InventorySwap.Probe.reconcileNow(player);
    }

    private static void outOfVoid(GameTestHelper helper, ServerPlayer player, ServerLevel overworld) {
        boolean moved = player.teleportTo(overworld, 0.5, 80.0, 0.5, Set.of(), 0.0F, 0.0F, false);
        helper.assertTrue(moved, "the teleport out of pocketdungeons:void took");
        InventorySwap.Probe.reconcileNow(player);
    }

    /** Something identifiable in a main slot, an armour slot and the offhand. */
    private static void fillSurvival(ServerPlayer player) {
        emptyInventory(player);
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().setItem(8, new ItemStack(Items.COOKED_BEEF, 32));
        player.getInventory().setItem(35, new ItemStack(Items.ENDER_PEARL, 4));
        player.getInventory().setItem(36, new ItemStack(Items.NETHERITE_BOOTS));
        player.getInventory().setItem(39, new ItemStack(Items.TURTLE_HELMET));
        player.getInventory().setItem(40, new ItemStack(Items.SHIELD));
    }

    private static void emptyInventory(ServerPlayer player) {
        for (int slot = 0; slot < 41; slot++) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    private static boolean isEmptyInventory(ServerPlayer player) {
        for (int slot = 0; slot < 41; slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) {
                return false;
            }
        }
        return player.containerMenu.getCarried().isEmpty();
    }

    /** Slots 0 to 40, copied. */
    private static List<ItemStack> liveSlots(ServerPlayer player) {
        List<ItemStack> out = new ArrayList<>(41);
        for (int slot = 0; slot < 41; slot++) {
            out.add(player.getInventory().getItem(slot).copy());
        }
        return out;
    }

    /** The first empty main slot in a snapshot list. */
    private static int firstFreeMainSlot(List<ItemStack> slots) {
        for (int i = 0; i < 36; i++) {
            if (slots.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameContents(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static int countOf(List<ItemStack> stacks, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Leaves the server as the test found it. A mock player left in the
     * {@code PlayerList} would be swept by the next test's
     * {@code reconcileAllNow}, and a stash left behind would outlive the run.
     */
    private static void cleanUp(MinecraftServer server, ServerPlayer player) {
        InventorySwap.Probe.forceStash(player, false, List.of());
        emptyInventory(player);
        server.getPlayerList().remove(player);
        InventorySwap.Probe.useDimensionForTesting(null);
    }
}
