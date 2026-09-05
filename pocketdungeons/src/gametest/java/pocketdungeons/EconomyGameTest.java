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
 * <p>Same package rationale as {@link CustodyGameTest}: {@link Fuel},
 * {@link Payout}, {@link RerollStation} and {@link CubeStation} are all
 * package-private, and sharing the package is cheaper than opening them.
 *
 * <h2>What is here and what is not</h2>
 *
 * <p>{@link GambleStation}'s draw is deliberately absent. Its money-moving half
 * is {@code handleTrade}, a private callback reachable only through an SGUI
 * {@code MerchantGui} the player clicks, and DISCOVERIES trap 10 is explicit
 * that nothing headless right-clicks a screen. Asserting a fake call into it
 * would prove the assertion, not the station. It stays a
 * {@code LIVE_TEST_PASS} row. What is covered here is the delivery primitive
 * every one of those paths ends in, {@link Payout#deliver}, which is where a
 * lost reward would actually be lost.
 */
@SuppressWarnings("removal")
public final class EconomyGameTest {

    /**
     * The banked-fuel ledger balances: what leaves the inventory arrives in the
     * balance, and what the balance spends does not come back as items.
     */
    @GameTest
    public void fuelBankingMovesUnitsWithoutMintingThem(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        requireFuelItem(helper);
        DungeonLog log = DungeonLog.forServer(server);
        clearFuel(log, player);
        emptyInventory(player);

        Fuel.grant(player, 10);
        helper.assertValueEqual(carriedFuel(player), 10, "a grant of ten put ten fuel in the inventory");
        helper.assertValueEqual(Fuel.banked(player), 0, "a grant banks nothing on its own");

        Fuel.bank(player, 10);
        helper.assertValueEqual(carriedFuel(player), 0, "banking took the fuel out of the inventory");
        helper.assertValueEqual(Fuel.banked(player), 10, "and put all ten in the balance");

        Fuel.spendBanked(player, 4);
        helper.assertValueEqual(Fuel.banked(player), 6, "spending four left six banked");
        helper.assertValueEqual(carriedFuel(player), 0,
                "spending the balance did not hand items back");

        cleanUp(server, log, player);
        helper.succeed();
    }

    /**
     * Banking more than is carried must credit only what was actually taken.
     *
     * <p>{@link Fuel#bank} removes what it can and then credits the full amount
     * it was asked for, on the documented assumption that the caller checked
     * first. That assumption is exactly the kind M63 refuses to take on trust:
     * an inventory can change between the check and the call (a second click, a
     * teleport, another mod moving a stack), and the failure mode is minting
     * fuel from nothing rather than refusing.
     */
    @GameTest
    public void bankingMoreThanCarriedDoesNotMintFuel(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        requireFuelItem(helper);
        DungeonLog log = DungeonLog.forServer(server);
        clearFuel(log, player);
        emptyInventory(player);

        Fuel.grant(player, 3);
        helper.assertValueEqual(carriedFuel(player), 3, "three fuel carried");

        Fuel.bank(player, 10);

        helper.assertValueEqual(carriedFuel(player), 0, "banking emptied what was carried");
        helper.assertValueEqual(Fuel.banked(player), 3,
                "only the three units that actually existed were credited");

        cleanUp(server, log, player);
        helper.succeed();
    }

    /**
     * PD-48: an untagged stack of the configured item is not currency.
     *
     * <p>Without this the fuel item's vanilla identity is the whole check, and
     * anything else in the world that hands out an echo shard is a mint.
     */
    @GameTest
    public void anUntaggedLookalikeIsNotFuel(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Item fuelItem = requireFuelItem(helper);
        DungeonLog log = DungeonLog.forServer(server);
        clearFuel(log, player);
        emptyInventory(player);

        player.getInventory().setItem(0, new ItemStack(fuelItem, 64));
        helper.assertValueEqual(carriedFuel(player), 0,
                "a bare stack of the fuel item does not count as fuel");

        Fuel.bank(player, 64);
        helper.assertValueEqual(Fuel.banked(player), 0,
                "and it cannot be banked either");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 64,
                "nor is it consumed by the attempt");

        cleanUp(server, log, player);
        helper.succeed();
    }

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

        // Materials both stations would charge, and an empty hand: the gear the
        // picker was built over is gone.
        player.getInventory().setItem(1, new ItemStack(Items.LAPIS_LAZULI, 64));
        player.getInventory().setItem(2, new ItemStack(Items.DIAMOND, 64));
        int lapisBefore = countIn(player, Items.LAPIS_LAZULI);
        int diamondBefore = countIn(player, Items.DIAMOND);

        RerollStation.handleReroll(player, "minecraft:sharpness");
        CubeStation.handleImbue(player, "pocketdungeons:some_power");

        helper.assertValueEqual(countIn(player, Items.LAPIS_LAZULI), lapisBefore,
                "a stale reroll click spent no lapis");
        helper.assertValueEqual(countIn(player, Items.DIAMOND), diamondBefore,
                "a stale imbue click spent no material");
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

    private static Item requireFuelItem(GameTestHelper helper) {
        Item item = Fuel.item();
        if (item == null) {
            helper.fail("the configured fuel item did not resolve; the economy cannot be asserted on");
        }
        return item;
    }

    /** How many marked fuel units the player is carrying, by {@link Fuel#isFuel}'s own rule. */
    private static int carriedFuel(ServerPlayer player) {
        int total = 0;
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (Fuel.isFuel(stack)) {
                total += stack.getCount();
            }
        }
        return total;
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

    private static void clearFuel(DungeonLog log, ServerPlayer player) {
        int banked = log.get(player.getUUID()).fuel();
        if (banked != 0) {
            log.addFuel(player.getUUID(), -banked);
        }
    }

    private static void emptyInventory(ServerPlayer player) {
        for (int slot = 0; slot < InventorySwap.LIVE_SLOTS; slot++) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    private static void cleanUp(MinecraftServer server, DungeonLog log, ServerPlayer player) {
        clearFuel(log, player);
        emptyInventory(player);
        server.getPlayerList().remove(player);
    }
}
