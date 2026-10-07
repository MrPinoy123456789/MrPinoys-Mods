package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * M63 currency conservation: a station or a payout may refuse, but it may never
 * charge without delivering, deliver without charging, or deliver twice.
 *
 * <p>Same package rationale as {@link CustodyGameTest}: {@link Payout},
 * {@link RerollStation} and {@link CubeStation} are all
 * package-private, and sharing the package is cheaper than opening them.
 *
 * <h2>What is here and what is not</h2>
 *
 * <p>The trade screens are deliberately absent: merchant offers open as a
 * vanilla {@code MerchantGui} the player clicks, and DISCOVERIES trap 10 is
 * explicit that nothing headless right-clicks a screen. Asserting a fake call
 * into the click path would prove the assertion, not the station. It stays a
 * {@code LIVE_TEST_PASS} row. What is covered here is the delivery primitive
 * every one of those paths ends in, {@link Payout#deliver}, which is where a
 * lost reward would actually be lost.
 */
@SuppressWarnings("removal")
public final class EconomyGameTest {

    /**
     * A reward handed to a player with nowhere to put it lands on the floor,
     * with its components intact, and exactly once.
     */
    @GameTest
    public void payoutOverflowDropsRatherThanVoids(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = standInALoadedChunk(helper);
        emptyInventory(player);

        // The delivery happens a few ticks in, not immediately. The mock player
        // starts at the world origin and has to be moved into the harness's own
        // force-loaded structure first, and a drop resolved before that move has
        // settled goes into an unloaded chunk, where addFreshEntity discards it
        // and returns false. That would read as the mod voiding the reward.
        helper.runAfterDelay(4L, () -> {
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            ItemStack reward = new ItemStack(Items.DIAMOND_SWORD);
            reward.set(DataComponents.CUSTOM_NAME, Component.literal("Marked"));
            Payout.deliver(player, reward);
        });

        helper.runAfterDelay(9L, () -> {
            int inInventory = countIn(player, Items.DIAMOND_SWORD);
            int onFloor = countNearby(server, player, Items.DIAMOND_SWORD);
            helper.assertValueEqual(inInventory + onFloor, 1,
                    "the reward exists exactly once, in a slot or on the floor");
            helper.assertValueEqual(inInventory, 0, "a full inventory could not take it");
            helper.assertTrue(namedRewardIsIntact(server, player),
                    "the dropped reward kept its components rather than being rebuilt bare");

            cleanUp(server, DungeonLog.forServer(server), player);
            helper.succeed();
        });
    }

    /**
     * The stale-click case both dialog stations share: the picker was opened
     * over a piece of gear, the gear left the player's hand, and the click
     * arrives anyway.
     *
     * <p>Both handlers re-read the live main-hand item rather than trusting
     * what the screen was built from. The property asserted is the one that
     * matters to a player: nothing was spent. Only the branches that refuse
     * before opening a replacement dialog are driven here, because a dialog
     * send is a client round trip and DISCOVERIES trap 10 puts that out of
     * headless reach.
     */
    @GameTest
    public void staleStationClicksSpendNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DungeonLog log = DungeonLog.forServer(server);
        emptyInventory(player);

        // The material the station would charge, and an empty hand: the gear
        // the picker was built over is gone.
        player.getInventory().setItem(1, new ItemStack(Items.LAPIS_LAZULI, 64));
        int lapisBefore = countIn(player, Items.LAPIS_LAZULI);

        RerollStation.handleReroll(player, "minecraft:sharpness");

        helper.assertValueEqual(countIn(player, Items.LAPIS_LAZULI), lapisBefore,
                "a stale reroll click spent no lapis");
        helper.assertValueEqual(countNearby(server, player, Items.LAPIS_LAZULI), 0,
                "and nothing was dropped on the floor instead of spent");

        cleanUp(server, log, player);
        helper.succeed();
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * A mock player standing somewhere its dropped items will actually exist.
     *
     * <p>{@code makeMockServerPlayerInLevel} leaves the player at the world
     * origin, which in a gametest world is nowhere near the test structure and
     * is not a loaded chunk. That matters for any scenario that asserts on a
     * drop: verified in the 26.2 jar, {@code LivingEntity.drop} ends in
     * {@code Level.addFreshEntity}, and an {@code addFreshEntity} into an
     * unloaded chunk is discarded and returns false rather than throwing. The
     * item is gone, silently, and reads exactly like the mod voiding a reward.
     * Moving the player into the structure the harness has already force-loaded
     * is what makes the difference between a real void and a dropped item
     * visible to an assertion.
     *
     * <p>The game mode matters just as much, and is the subtler of the two. A
     * mock player is created in creative, and {@code Inventory.add} has a
     * creative-only branch (verified in the 26.2 bytecode: a
     * {@code Player.hasInfiniteMaterials} test guarded by
     * {@code ItemStack.setCount(0)}) that empties an overflowing stack and
     * reports success rather than handing the remainder back. That is correct
     * for a creative player, who needs no remainder, but it means
     * {@link Payout#deliver}'s {@code stack.isEmpty()} test passes with the
     * item having gone nowhere. An overflow scenario run in creative therefore
     * cannot tell a working payout from a voided one, whichever way the code
     * behaves. Survival is the mode the assertion is about.
     */
    @SuppressWarnings("removal")
    private static ServerPlayer standInALoadedChunk(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        boolean moved = player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(),
                where.getZ() + 0.5, java.util.Set.of(), 0.0F, 0.0F, false);
        helper.assertTrue(moved, "the mock player reached the loaded test structure");
        return player;
    }

    private static int countIn(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countNearby(MinecraftServer server, ServerPlayer player, Item item) {
        int total = 0;
        AABB near = player.getBoundingBox().inflate(5.0);
        for (ServerLevel level : server.getAllLevels()) {
            for (ItemEntity dropped : level.getEntitiesOfClass(ItemEntity.class, near)) {
                if (dropped.getItem().is(item)) {
                    total += dropped.getItem().getCount();
                }
            }
        }
        return total;
    }

    private static boolean namedRewardIsIntact(MinecraftServer server, ServerPlayer player) {
        AABB near = player.getBoundingBox().inflate(5.0);
        for (ServerLevel level : server.getAllLevels()) {
            for (ItemEntity dropped : level.getEntitiesOfClass(ItemEntity.class, near)) {
                if (dropped.getItem().is(Items.DIAMOND_SWORD)) {
                    return dropped.getItem().get(DataComponents.CUSTOM_NAME) != null;
                }
            }
        }
        return false;
    }

    private static void emptyInventory(ServerPlayer player) {
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    private static void cleanUp(MinecraftServer server, DungeonLog log, ServerPlayer player) {
        emptyInventory(player);
        server.getPlayerList().remove(player);
    }
}
