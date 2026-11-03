package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Optional;

/**
 * What a coin is. Identity is the {@code custom_data} marker
 * {@code {"chartercurrency":{"coin":"<id>"}}} and nothing else: not the item, not the
 * name. A plain nugget is not coin, and an anvil-renamed nugget is not coin.
 *
 * <p>Only the marker key is matched, not the whole component patch, so extra components
 * added to a coin later (a trim, a glint) do not stop it being recognised.
 */
final class CoinStacks {

    static final String ROOT = "chartercurrency";
    static final String KEY = "coin";

    private CoinStacks() {}

    /** The denomination id stamped on a stack, if it is a coin at all. */
    static Optional<String> markerOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return Optional.empty();
        CompoundTag root = data.copyTag();
        if (!root.contains(ROOT)) return Optional.empty();
        return root.getCompoundOrEmpty(ROOT).getString(KEY);
    }

    /** True if the stack carries the marker for exactly this denomination. */
    static boolean isCoin(ItemStack stack, Denomination denomination) {
        return markerOf(stack).map(denomination.id()::equals).orElse(false);
    }

    /** True if the stack is a coin of any denomination the currency defines. */
    static boolean isAnyCoin(ItemStack stack, Currency coin) {
        Optional<String> marker = markerOf(stack);
        if (marker.isEmpty()) return false;
        for (Denomination d : coin.denominations()) {
            if (d.id().equals(marker.get())) return true;
        }
        return false;
    }

    /** True for an unmarked nugget of a coin denomination's base item. */
    static boolean isPlainNugget(ItemStack stack, Currency coin) {
        if (stack == null || stack.isEmpty() || markerOf(stack).isPresent()) return false;
        for (Denomination d : coin.denominations()) {
            Optional<Item> item = ItemBank.resolve(d.itemId());
            if (item.isPresent() && stack.is(item.get())) return true;
        }
        return false;
    }

    /** One coin stack: marker, item_name and one lore line. */
    static ItemStack make(Denomination denomination, Currency coin, int count) {
        Item item = ItemBank.resolve(denomination.itemId())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown coin item " + denomination.itemId()));
        ItemStack stack = new ItemStack(item, count);

        CompoundTag inner = new CompoundTag();
        inner.putString(KEY, denomination.id());
        CompoundTag root = new CompoundTag();
        root.put(ROOT, inner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));

        stack.set(DataComponents.ITEM_NAME, Component.literal(denomination.singular()));
        stack.set(DataComponents.LORE, lore(denomination, coin));
        return stack;
    }

    /**
     * The coin's lore: its worth in the next coin down ({@code Charter coin, worth 8 Crowns}),
     * and how to bank it ({@link CoinBankClick}).
     */
    static ItemLore lore(Denomination denomination, Currency coin) {
        Denomination below = null;
        for (Denomination d : coin.denominations()) {
            if (d.value() < denomination.value() && (below == null || d.value() > below.value())) below = d;
        }
        String worth = below == null
                ? "Charter coin"
                : "Charter coin, worth " + below.describe(denomination.value() / below.value());
        return new ItemLore(List.of(
                Component.literal(worth)
                        .withStyle(style -> style.withItalic(false).applyFormat(ChatFormatting.GRAY)),
                Component.literal("Right-click: bank this stack")
                        .withStyle(style -> style.withItalic(false).applyFormat(ChatFormatting.YELLOW))));
    }

    /**
     * Brings a coin made by an older release up to today's lore, so it stacks with new
     * coin and shows the bank hint. Returns true if the stack was changed. The marker,
     * the count and everything else are left alone.
     */
    static boolean refresh(ItemStack stack, Currency coin) {
        Optional<String> marker = markerOf(stack);
        if (marker.isEmpty()) return false;
        for (Denomination d : coin.denominations()) {
            if (!d.id().equals(marker.get())) continue;
            ItemLore want = lore(d, coin);
            if (want.equals(stack.get(DataComponents.LORE))) return false;
            stack.set(DataComponents.LORE, want);
            return true;
        }
        return false;
    }
}
