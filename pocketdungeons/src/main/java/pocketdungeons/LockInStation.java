package pocketdungeons;

import eu.pb4.sgui.api.gui.MerchantGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Mending as an expensive "lock in" (playtest 2026-10-02-1, owner decision): Mending
 * no longer drops or rerolls onto gear; a librarian beside a lectern
 * ({@link LibrarianNPC}) adds it for a large emerald price, and the piece is then
 * <strong>locked in</strong>. Locked gear is the end of a piece's journey: the
 * salvage bench and the reroll station both refuse it.
 *
 * <p>The screen is the villager trading UI with one trade: the emeralds are the
 * price and the result is a preview of the held piece with Mending. The live
 * main-hand item is re-read and compared at trade time, the same staleness
 * discipline the other stations use.
 */
final class LockInStation {

    private static final String LOCKED_KEY = "locked";

    private LockInStation() {}

    /** Whether {@code stack} has been locked in. */
    static boolean isLocked(ItemStack stack) {
        return !stack.isEmpty() && StationSupport.readIntMarker(stack, LOCKED_KEY) != 0;
    }

    /** Gear that can take Mending: damageable, not already locked, no Mending already. */
    static boolean eligible(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem() || isLocked(stack)) {
            return false;
        }
        return stack.getEnchantments().getLevel(mending(player)) <= 0;
    }

    private static Holder<Enchantment> mending(ServerPlayer player) {
        return player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.MENDING);
    }

    /** {@code stack} with Mending I added and the locked flag and lore set (a copy). */
    static ItemStack lockedCopy(ServerPlayer player, ItemStack stack) {
        ItemStack copy = stack.copy();
        ItemEnchantments.Mutable enchants = new ItemEnchantments.Mutable(copy.getEnchantments());
        enchants.set(mending(player), 1);
        copy.set(DataComponents.ENCHANTMENTS, enchants.toImmutable());
        CustomData data = copy.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = data.copyTag();
        CompoundTag mine = root.getCompound(PocketDungeonsMod.MOD_ID).orElseGet(CompoundTag::new);
        mine.putInt(LOCKED_KEY, 1);
        root.put(PocketDungeonsMod.MOD_ID, mine);
        copy.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        List<Component> lore = new ArrayList<>(copy.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        lore.add(Component.literal("Locked in").withStyle(ChatFormatting.AQUA).withStyle(s -> s.withItalic(false)));
        copy.set(DataComponents.LORE, new ItemLore(lore));
        return copy;
    }

    /**
     * Opens the lock-in screen for the gear in {@code player}'s main hand, or
     * says why it will not.
     *
     * @return whether the screen opened
     */
    static boolean openGui(ServerPlayer player) {
        int level = DungeonLog.forServer(player.level().getServer()).get(player.getUUID()).keystoneLevel();
        if (StationSupport.levelTooLow(player, level, PocketDungeonsConfig.lockInUnlockLevel(), "librarian")) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        if (isLocked(held)) {
            say(player, "That piece is already locked in.");
            return false;
        }
        if (!eligible(player, held)) {
            say(player, "Hold the gear you want locked in. It cannot already have Mending.");
            return false;
        }
        int cost = PocketDungeonsConfig.lockInEmeralds();
        ItemStack snapshot = held.copy();
        ItemStack preview = lockedCopy(player, held);
        preview.set(DataComponents.LORE, new ItemLore(withCost(preview, cost)));

        MerchantGui gui = new MerchantGui(player, false) {
            @Override
            public boolean onTrade(MerchantOffer offer) {
                return handleTrade(player, snapshot, cost, this.merchantInventory);
            }
        };
        gui.setTitle(Component.literal("Lock In"));
        gui.setIsLeveled(false);
        gui.addTrade(new MerchantOffer(new ItemCost(Items.EMERALD, cost), preview, Integer.MAX_VALUE, 0, 0f));
        gui.open();
        return true;
    }

    private static List<Component> withCost(ItemStack preview, int cost) {
        List<Component> lore = new ArrayList<>(preview.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        lore.add(Component.literal("Costs " + cost + " emeralds. This ends its journey: it cannot be salvaged or rerolled.")
                .withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(false)));
        return lore;
    }

    private static boolean handleTrade(ServerPlayer player, ItemStack snapshot, int cost,
                                       MerchantContainer merchantInventory) {
        ItemStack held = player.getMainHandItem();
        if (!ItemStack.isSameItemSameComponents(held, snapshot) || !eligible(player, held)) {
            say(player, "You are no longer holding that piece.");
            return false;
        }
        ItemStack payment = merchantInventory.getItem(0);
        if (!payment.is(Items.EMERALD) || payment.getCount() < cost) {
            say(player, "You need " + cost + " emeralds.");
            return false;
        }
        ItemStack locked = lockedCopy(player, held);
        payment.shrink(cost);
        if (payment.isEmpty()) {
            merchantInventory.setItem(0, ItemStack.EMPTY);
        }
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, locked);
        say(player, locked.getHoverName().getString() + " is locked in.");
        PlaytestJournal.lockIn(player, locked.getItem().toString(), cost);
        player.closeContainer();
        return false;
    }

    private static void say(ServerPlayer player, String line) {
        player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.AQUA));
    }
}
