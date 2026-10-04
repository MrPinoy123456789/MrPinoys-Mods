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
 * <p><strong>The pack is the wallet (2026-10-02).</strong> A Greater door
 * takes its echo shards straight from the player's inventory when the commit
 * lever is pulled; with too few, nothing happens and the player is told how
 * many it needs. There used to be a bank: shards fed into the staging room's
 * engine terminal and spent from a per-player balance. It is gone, and
 * {@link #refundBanked} hands any balance left over back as shards.
 *
 * <p><strong>Door 1 is the only source.</strong> Nothing else in this mod's
 * loot tables grants {@link PocketDungeonsConfig#fuelItem()} (echo shards by
 * default), by design: a Greater-tier room dropping fuel of its own would let
 * the premium path fund itself, which is exactly the self-funding risk the
 * M12 plan section warns against. {@link RunLifecycle#completeRun} grants
 * {@link PocketDungeonsConfig#fuelPerFreeRun()} directly, guaranteed, on
 * every completed free-door run; nothing here rolls a chance at it.
 *
 * <p><strong>Plain shards count; other mods' shards do not (PD-48).</strong>
 * Any stack of the configured item pays, whether this mod granted it or the
 * player brought it, unless it carries another mod's {@code custom_data}: an
 * echo shard re-skinned by other content (a Kamu Totems Boss Stone, say) is
 * that content's item, not fuel. Shards this mod minted before 2026-10-02
 * carry the old {@link #KEY_FUEL} marker and still count. New grants are plain
 * shards, so they stack with vanilla ones.
 */
final class Fuel {

    private static final ConfiguredItem FUEL_ITEM = new ConfiguredItem("fuelItem",
            PocketDungeonsConfig::fuelItem,
            "the Greater doors will refuse every choice: nothing can ever pay their cost.");

    /** The marker on shards minted before 2026-10-02; still accepted, never written. */
    private static final String KEY_FUEL = "fuel";

    private Fuel() {}

    /** Resolves the configured item once, so a typo is a boot-time log line. */
    static void warmUp() {
        FUEL_ITEM.get();
    }

    /** The configured fuel item, or {@code null} if it does not resolve. The engine screen names it. */
    static Item item() {
        return FUEL_ITEM.get();
    }

    /** Whether {@code stack} pays for a Greater door: the configured item, and not another mod's re-skin. */
    static boolean isFuel(ItemStack stack) {
        Item item = FUEL_ITEM.get();
        return item != null && !stack.isEmpty() && stack.is(item) && plainOrOurs(stack);
    }

    /** No custom data at all, or only this mod's old fuel marker. */
    private static boolean plainOrOurs(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return true;
        }
        CompoundTag tag = data.copyTag();
        if (tag.size() != 1) {
            return false;
        }
        CompoundTag mine = tag.getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        return mine != null && mine.size() == 1 && mine.getBooleanOr(KEY_FUEL, false);
    }

    /** How many shards {@code player} carries that a Greater door can take. */
    static int carried(ServerPlayer player) {
        if (FUEL_ITEM.get() == null) {
            return 0;
        }
        return player.getInventory()
                .clearOrCountMatchingItems(Fuel::isFuel, 0, new SimpleContainer(0));
    }

    /**
     * Removes up to {@code amount} shards from {@code player}'s inventory and
     * returns how many it actually took, which is fewer than asked for when
     * fewer are carried. Callers check {@link #carried} first.
     *
     * <p>{@code Inventory.clearOrCountMatchingItems}'s third parameter is an
     * extra container it clears from as well as the inventory itself and
     * whatever is on the cursor of an open menu (verified in bytecode: vanilla
     * passes the crafting grid there, so an in-progress craft's ingredients
     * count too). Nothing here should touch a crafting grid, so an empty,
     * unrelated {@link SimpleContainer} stands in for "nothing else." With a
     * count of zero the same call only counts, which is {@link #carried}.
     */
    static int take(ServerPlayer player, int amount) {
        if (FUEL_ITEM.get() == null || amount <= 0) {
            return 0;
        }
        return player.getInventory()
                .clearOrCountMatchingItems(Fuel::isFuel, amount, new SimpleContainer(0));
    }

    /**
     * Grants {@code amount} plain shards, via {@link Payout#deliver} so a full
     * inventory drops the overflow at the player's feet rather than voiding it.
     */
    static void grant(ServerPlayer player, int amount) {
        Item item = FUEL_ITEM.get();
        if (item == null || amount <= 0) {
            return;
        }
        Payout.deliver(player, new ItemStack(item, amount));
    }

    /**
     * {@link #grant}, then tells the player and the playtest journal where the
     * shards came from ({@code interval}, {@code floor}, {@code ordeal},
     * {@code free_door}).
     */
    static void grantFrom(ServerPlayer player, int amount, String source) {
        if (amount <= 0 || FUEL_ITEM.get() == null) {
            return;
        }
        grant(player, amount);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "+" + amount + " echo shard" + (amount == 1 ? "" : "s") + " (" + source.replace('_', ' ') + ")")
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        PlaytestJournal.echoShards(player, amount, source);
    }

    /** Whether a per-player chance rolls true; kept apart so a test can pin it. */
    static boolean rollChance(net.minecraft.util.RandomSource random, double chance) {
        return chance >= 1.0 || (chance > 0.0 && random.nextDouble() < chance);
    }

    /**
     * Hands back, as shards, whatever {@code player} still had banked in the
     * retired engine terminal, and empties that balance, so nobody loses
     * shards to the bank's removal. Runs wherever a Greater door is weighed
     * (choosing, committing, touching the engine): all in the staging room,
     * so the shards land in the dungeon pack that pays for the door, not in a
     * survival inventory the dungeon cannot reach.
     */
    static void refundBanked(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        DungeonLog log = DungeonLog.forServer(server);
        int banked = log.get(player.getUUID()).fuel();
        if (banked <= 0) {
            return;
        }
        log.addFuel(player.getUUID(), -banked);
        grant(player, banked);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "The engine no longer holds echo shards: your " + banked + " are back in your pack."
                        + " Greater doors take them from there when you pull the lever.")
                .withStyle(net.minecraft.ChatFormatting.AQUA));
    }
}
