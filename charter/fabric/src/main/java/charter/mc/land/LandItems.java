package charter.mc.land;

import charter.mc.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * The items that make land (PLAN.md §7.1): a vanilla item carrying a
 * {@code charter} tag in {@code custom_data}, a custom name and lore, so a vanilla
 * client needs no resource pack. The nexus is a lodestone, the war banner a red
 * banner.
 */
public final class LandItems {

    public static final String KEY = "charter";
    public static final String NEXUS = "nexus";
    public static final String WAR_BANNER = "war_banner";

    private LandItems() {
    }

    public static ItemStack nexus() {
        ItemStack stack = new ItemStack(Items.LODESTONE);
        stamp(stack, NEXUS, Text.get("land.item.nexus"), ChatFormatting.AQUA, "land.item.nexus_lore");
        return stack;
    }

    public static ItemStack warBanner() {
        ItemStack stack = new ItemStack(Items.BANNER.red());
        stamp(stack, WAR_BANNER, Text.get("land.item.banner"), ChatFormatting.RED, "land.item.banner_lore");
        return stack;
    }

    /** Which of ours this is ({@link #NEXUS} or {@link #WAR_BANNER}), or null. */
    public static String kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String kind = data.copyTag().getStringOr(KEY, "");
        return kind.isBlank() ? null : kind;
    }

    private static void stamp(ItemStack stack, String kind, String name, ChatFormatting colour, String loreKey) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY, kind);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(colour));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                (Component) Component.literal(Text.get(loreKey)).withStyle(ChatFormatting.GRAY))));
    }
}
