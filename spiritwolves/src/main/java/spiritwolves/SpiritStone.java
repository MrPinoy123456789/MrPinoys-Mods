package spiritwolves;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Repairable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stack construction and {@code custom_data} read/write for the Spirit Stone.
 *
 * <p>v3 (SPEC.md section 16.2): the stone is a thin marker + charge holder.
 * The wolf itself, its journal, and its progression live in
 * {@link PlayerWolfRegistry}, keyed by player, not by stone. {@code
 * custom_data} on the stone is just {@code { spiritwolves: { bound: bool } }}
 * plus cached name/collar so lore can be rebuilt without a live record.
 */
public final class SpiritStone {

    /** The NBT key inside custom_data. */
    public static final String KEY = "spiritwolves";

    private static final int MAX_CHARGES = 4;

    /** Hard cap on lore lines. Pinned name/collar/charges lines win; journal lines are trimmed oldest-first. */
    private static final int MAX_LORE_LINES = 6;

    private SpiritStone() {}

    /** Builds a fresh, unbound stone stack. */
    public static ItemStack createUnbound(int count) {
        ItemStack stack = new ItemStack(Items.ECHO_SHARD, count);
        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Spirit Stone").withStyle(ChatFormatting.AQUA));
        stack.set(DataComponents.LORE, new ItemLore(new ArrayList<>(List.of(
                Component.literal("Use on a tamed wolf to bind its soul.")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)))));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = new CompoundTag();
            inner.putBoolean("bound", false);
            tag.put(KEY, inner);
        });
        return stack;
    }

    /** True if this stack is a Spirit Stone (bound or not). */
    public static boolean is(ItemStack stack) {
        return tag(stack) != null;
    }

    public static boolean isBound(ItemStack stack) {
        CompoundTag tag = tag(stack);
        return tag != null && tag.getBooleanOr("bound", false);
    }

    public static boolean isUnbound(ItemStack stack) {
        return is(stack) && !isBound(stack);
    }

    /** Reads the inner {@code spiritwolves} compound, or null if this is not a stone. */
    private static CompoundTag tag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        // getCompoundOrEmpty would hand back an empty compound for any stack that
        // merely has custom_data (e.g. wondrous items), making every one of them
        // look like an unbound stone. Only a real spiritwolves compound counts.
        return data.copyTag().getCompound(KEY).orElse(null);
    }

    /**
     * Marks a stone bound and applies the vanilla damage/repair components the
     * anvil reads (SPEC.md section 4). Charges start full. Does not touch the
     * registry -- the caller (Binding) writes the {@link WolfRecord} separately.
     */
    public static void bind(ItemStack stack, String wolfName, String collar) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = new CompoundTag();
            inner.putBoolean("bound", true);
            tag.put(KEY, inner);
        });

        stack.set(DataComponents.MAX_DAMAGE, MAX_CHARGES);
        stack.set(DataComponents.DAMAGE, 0);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.REPAIRABLE,
                new Repairable(HolderSet.direct(Items.DIAMOND.builtInRegistryHolder())));

        applyName(stack, wolfName);
        refreshLore(stack, wolfName, collar, null);
    }

    /**
     * Reverts a bound stone to unbound -- release (SPEC.md section 16.5) or
     * true death (section 16.7). Charges, repair, and stack-size components are
     * stripped so the item behaves exactly like a freshly created unbound stone.
     */
    public static void revertToUnbound(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = new CompoundTag();
            inner.putBoolean("bound", false);
            tag.put(KEY, inner);
        });
        stack.remove(DataComponents.MAX_DAMAGE);
        stack.remove(DataComponents.DAMAGE);
        stack.remove(DataComponents.MAX_STACK_SIZE);
        stack.remove(DataComponents.REPAIRABLE);
        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Spirit Stone").withStyle(ChatFormatting.AQUA));
        stack.set(DataComponents.LORE, new ItemLore(new ArrayList<>(List.of(
                Component.literal("Use on a tamed wolf to bind its soul.")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)))));
    }

    private static void applyName(ItemStack stack, String wolfName) {
        String displayName = (wolfName != null && !wolfName.isBlank())
                ? wolfName + "'s Spirit Stone"
                : "Spirit Stone";
        stack.set(DataComponents.ITEM_NAME,
                Component.literal(displayName).withStyle(ChatFormatting.AQUA));
    }

    /**
     * Rebuilds the lore from the holder's {@link WolfRecord}. {@code record}
     * may be null: a bound stone with no record is an <em>orphan</em> (SPEC.md
     * section 16.2/16.7) and gets a dedicated "silent" lore; an unbound stone
     * keeps its plain instructional lore.
     */
    public static void refreshLore(ItemStack stack, WolfRecord record) {
        refreshLore(stack, record == null ? null : record.wolfName,
                record == null ? null : record.collar,
                record == null ? null : record.journalLines());
    }

    private static void refreshLore(ItemStack stack, String wolfName, String collar, List<String> journal) {
        if (!isBound(stack)) {
            return;
        }
        if (journal == null && wolfName == null && collar == null) {
            // Orphan: bound stone, no live record to render.
            applyName(stack, null);
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("The stone is silent.")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))));
            return;
        }

        applyName(stack, wolfName);

        int charges = chargesRemaining(stack);
        List<Component> lines = new ArrayList<>();
        if (wolfName != null && !wolfName.isBlank()) {
            lines.add(Component.literal(wolfName).withStyle(ChatFormatting.GOLD));
        }
        if (collar != null && !collar.isBlank()) {
            lines.add(Component.literal("Collar: " + collar).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal("Charges: " + charges + "/" + MAX_CHARGES)
                .withStyle(charges > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));

        if (journal != null) {
            int room = Math.max(0, MAX_LORE_LINES - lines.size());
            // Drop the oldest journal lines first when there isn't room for all of them.
            for (String entry : journal.subList(Math.max(0, journal.size() - room), journal.size())) {
                lines.add(Component.literal(entry)
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            }
        }

        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    /** Charges remaining, derived from damage: max_damage - damage. */
    public static int chargesRemaining(ItemStack stack) {
        Integer maxDamage = stack.get(DataComponents.MAX_DAMAGE);
        if (maxDamage == null) {
            return 0;
        }
        int damage = stack.getDamageValue();
        return Math.max(0, maxDamage - damage);
    }

    public static int maxCharges() {
        return MAX_CHARGES;
    }

    /** Consumes one charge (increments damage by 1), clamped to max_damage. */
    public static void consumeCharge(ItemStack stack) {
        int max = stack.getMaxDamage();
        int newDamage = Math.min(max, stack.getDamageValue() + 1);
        stack.setDamageValue(newDamage);
    }
}
