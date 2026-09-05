package pocketdungeons;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

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
 *
 * <p><strong>Matching by item type alone is not enough (PD-48).</strong> The
 * configured fuel item defaults to a vanilla item other content, in this mod
 * and in other mods sharing the world, can reasonably use for something else
 * entirely, including as a base for its own re-skinned item via
 * {@code set_custom_data}. Every fuel stack this class ever creates carries
 * the {@link #KEY_FUEL} marker under {@code custom_data.pocketdungeons}, the
 * same convention {@link CubeStation}'s {@code tier} tag uses, and every
 * check here requires it. A stack that merely happens to share the
 * configured item type, minted by anything other than {@link #grant}, does
 * not count as fuel.
 */
final class Fuel {

    private static final ConfiguredItem FUEL_ITEM = new ConfiguredItem("fuelItem",
            PocketDungeonsConfig::fuelItem,
            "the Greater doors will refuse every choice: nothing can ever pay their cost.");

    private static final String KEY_FUEL = "fuel";

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

    /** Whether {@code stack} is the configured fuel item, minted by {@link #grant}. */
    static boolean isFuel(ItemStack stack) {
        Item item = FUEL_ITEM.get();
        return item != null && !stack.isEmpty() && stack.is(item) && isMarked(stack);
    }

    private static boolean isMarked(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return false;
        }
        CompoundTag mine = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        return mine != null && mine.getBooleanOr(KEY_FUEL, false);
    }

    /**
     * How much fuel {@code player} has banked in an engine terminal. This,
     * not how much they are carrying, is what a Greater door can spend.
     */
    static int banked(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0 : DungeonLog.forServer(server).get(player.getUUID()).fuel();
    }

    /**
     * Moves up to {@code amount} units out of {@code player}'s inventory and
     * into their banked balance: what the engine terminal does with a fed
     * stack.
     *
     * <p>Credits what was actually removed, not what was asked for. The caller
     * is still expected to have checked the carried count, but M63's rule is
     * that a conservation guarantee may not rest on a caller's promise: the
     * inventory can change between the check and this call (a second click on
     * the terminal, a swap, another inventory mod moving a stack), and crediting
     * the requested amount regardless would mint fuel out of nothing rather
     * than bank it. {@link #spend} already knows how many it took, so the
     * honest number is free.
     */
    static void bank(ServerPlayer player, int amount) {
        MinecraftServer server = player.level().getServer();
        if (server == null || amount <= 0) {
            return;
        }
        int taken = spend(player, amount);
        if (taken > 0) {
            DungeonLog.forServer(server).addFuel(player.getUUID(), taken);
        }
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
     * Removes up to {@code amount} units from {@code player}'s inventory and
     * returns how many it actually took, which is fewer than asked for when
     * fewer are carried.
     *
     * <p>{@code Inventory.clearOrCountMatchingItems}'s third parameter is an
     * extra container it clears from as well as the inventory itself and
     * whatever is on the cursor of an open menu (verified in bytecode: vanilla
     * passes the crafting grid there, so an in-progress craft's ingredients
     * count too). Nothing here should touch a crafting grid, so an empty,
     * unrelated {@link SimpleContainer} stands in for "nothing else."
     */
    private static int spend(ServerPlayer player, int amount) {
        if (FUEL_ITEM.get() == null || amount <= 0) {
            return 0;
        }
        return player.getInventory()
                .clearOrCountMatchingItems(Fuel::isFuel, amount, new SimpleContainer(0));
    }

    /**
     * Grants {@code amount} units, via {@link Payout#deliver} so a full
     * inventory drops the overflow at the player's feet rather than voiding
     * it. Marked with {@link #KEY_FUEL} so it, and only it, counts as fuel
     * everywhere else in this class (PD-48).
     */
    static void grant(ServerPlayer player, int amount) {
        Item item = FUEL_ITEM.get();
        if (item == null || amount <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(item, amount);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag mine = new CompoundTag();
            mine.putBoolean(KEY_FUEL, true);
            tag.put(PocketDungeonsMod.MOD_ID, mine);
        });
        Payout.deliver(player, stack);
    }
}
