package pocketdungeons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * M12's Greater-door currency: what door 1 pays out and doors 2/3 cost.
 * Configured, not hardcoded, following the {@link ConfiguredItem} pattern
 * {@link Keystone} and {@link TrialContent} already use.
 *
 * <p><strong>The engine terminal is the only wallet.</strong> A Greater door
 * is paid from {@link #banked}, the per-player balance in {@link DungeonLog},
 * never from what a player happens to be carrying. Fuel items are how fuel
 * travels: door 1 pays them out, the player carries them to a room's
 * respawn-anchor engine and feeds them in, and only then can a Greater door
 * spend them. Before this the engine consumed a shard and wrote a respawn
 * anchor charge that nothing in the mod ever read, while the doors charged
 * against the inventory, so feeding the engine destroyed fuel outright.
 *
 * <p><strong>Door 1 is the only source.</strong> Nothing else in this mod's
 * loot tables grants {@link PocketDungeonsConfig#fuelItem()} (echo shards by
 * default), by design: a Greater-tier room dropping fuel of its own would let
 * the premium path fund itself, which is exactly the self-funding risk the
 * M12 plan section warns against. {@link RunLifecycle#completeRun} grants
 * {@link PocketDungeonsConfig#fuelPerFreeRun()} directly, guaranteed, on
 * every completed free-door run; nothing here rolls a chance at it.
 */
final class Fuel {

    private static final ConfiguredItem FUEL_ITEM = new ConfiguredItem("fuelItem",
            PocketDungeonsConfig::fuelItem,
            "the Greater doors will refuse every choice: nothing can ever pay their cost.");

    private Fuel() {}

    /** Resolves the configured item once, so a typo is a boot-time log line. */
    static void warmUp() {
        FUEL_ITEM.get();
    }

    /**
     * The configured fuel item, or {@code null} if it does not resolve. The
     * engine screen names it ({@code DungeonScreen.engineContent}); the
     * engine handler tests held stacks against it ({@link #isFuel}).
     */
    static Item item() {
        return FUEL_ITEM.get();
    }

    /** Whether {@code stack} is the configured fuel item. */
    static boolean isFuel(ItemStack stack) {
        Item item = FUEL_ITEM.get();
        return item != null && !stack.isEmpty() && stack.is(item);
    }

    /**
     * How many units of fuel {@code player} is carrying. {@code 0} if the
     * configured item does not resolve to anything, rather than throwing: an
     * unresolvable fuel item should read as "can never afford it", the same
     * as any other {@link ConfiguredItem} failure mode in this mod.
     */
    static int count(ServerPlayer player) {
        Item item = FUEL_ITEM.get();
        return item == null ? 0 : player.getInventory().countItem(item);
    }

    /**
     * How much fuel {@code player} has banked in an engine terminal. This, not
     * {@link #count}, is what a Greater door can spend.
     */
    static int banked(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0 : DungeonLog.forServer(server).get(player.getUUID()).fuel();
    }

    /**
     * Moves {@code amount} units out of {@code player}'s inventory and into
     * their banked balance: what the engine terminal does with a fed stack. The
     * caller has already checked that this much is actually carried.
     */
    static void bank(ServerPlayer player, int amount) {
        MinecraftServer server = player.level().getServer();
        if (server == null || amount <= 0) {
            return;
        }
        spend(player, amount);
        DungeonLog.forServer(server).addFuel(player.getUUID(), amount);
    }

    /** Debits {@code amount} from the banked balance, for a Greater door's cost. */
    static void spendBanked(ServerPlayer player, int amount) {
        MinecraftServer server = player.level().getServer();
        if (server == null || amount <= 0) {
            return;
        }
        DungeonLog.forServer(server).addFuel(player.getUUID(), -amount);
    }

    /**
     * Removes {@code amount} units from {@code player}'s inventory. Caller's
     * responsibility to have checked {@link #count} first; this does not
     * refuse a short count; it just cannot remove more than exists.
     *
     * <p>{@code Inventory.clearOrCountMatchingItems}'s third parameter is an
     * extra container it clears from as well as the inventory itself and
     * whatever is on the cursor of an open menu (verified in bytecode: vanilla
     * passes the crafting grid there, so an in-progress craft's ingredients
     * count too). Nothing here should touch a crafting grid, so an empty,
     * unrelated {@link SimpleContainer} stands in for "nothing else."
     */
    static void spend(ServerPlayer player, int amount) {
        Item item = FUEL_ITEM.get();
        if (item == null || amount <= 0) {
            return;
        }
        player.getInventory().clearOrCountMatchingItems(stack -> stack.is(item), amount, new SimpleContainer(0));
    }

    /**
     * Grants {@code amount} units, via {@link Payout#deliver} so a full
     * inventory drops the overflow at the player's feet rather than voiding
     * it.
     */
    static void grant(ServerPlayer player, int amount) {
        Item item = FUEL_ITEM.get();
        if (item == null || amount <= 0) {
            return;
        }
        Payout.deliver(player, new ItemStack(item, amount));
    }
}
