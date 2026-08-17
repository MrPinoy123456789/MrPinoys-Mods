package cobblebending;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.UseCooldown;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Item construction for the two Focuses. They are vanilla items stamped with
 * {@code custom_data}, never registry entries.
 */
public final class Focus {

    public static final String KEY = "cobblebending";
    public static final String HURL = "hurl";
    public static final String WALL = "wall";

    private Focus() {}

    public static ItemStack hurl() {
        ItemStack stack = new ItemStack(Items.FIREWORK_STAR, 1);
        stack.set(DataComponents.ITEM_NAME, Component.literal("Hurl Focus").withStyle(ChatFormatting.GRAY));
        stack.set(DataComponents.LORE, lore(
                "Hold to gather stone. Release to throw.",
                "Longer holds throw heavier."
        ));
        stack.set(DataComponents.FIREWORK_EXPLOSION, new FireworkExplosion(
                FireworkExplosion.Shape.SMALL_BALL,
                IntList.of(0x888888, 0x777777), IntList.of(), false, false
        ));
        stamp(stack, HURL);
        applyCommon(stack, ItemUseAnimation.BRUSH, 8.0F, HURL);
        return stack;
    }

    public static ItemStack wall() {
        ItemStack stack = new ItemStack(Items.HEART_OF_THE_SEA, 1);
        stack.set(DataComponents.ITEM_NAME, Component.literal("Wall Focus").withStyle(ChatFormatting.AQUA));
        stack.set(DataComponents.LORE, lore(
                "Hold and release to raise a wall.",
                "Look down to lay a bridge.",
                "Sneak-use to recall your wall."
        ));
        stamp(stack, WALL);
        applyCommon(stack, ItemUseAnimation.BOW, 20.0F, WALL);
        return stack;
    }

    public static boolean is(ItemStack stack) {
        return stack != null && !stack.isEmpty() && kind(stack) != null;
    }

    public static String kind(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (tag == null || !tag.contains("focus")) {
            return null;
        }
        return tag.getStringOr("focus", "");
    }

    public static boolean isHurl(ItemStack stack) {
        return HURL.equals(kind(stack));
    }

    public static boolean isWall(ItemStack stack) {
        return WALL.equals(kind(stack));
    }

    public static void ensureConsumable(ItemStack stack) {
        if (is(stack) && !stack.has(DataComponents.CONSUMABLE)) {
            String k = kind(stack);
            applyCommon(stack, HURL.equals(k) ? ItemUseAnimation.BRUSH : ItemUseAnimation.BOW,
                    HURL.equals(k) ? 8.0F : 20.0F, k);
        }
    }

    private static CompoundTag tag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        CompoundTag root = data.copyTag();
        return root.getCompoundOrEmpty(KEY);
    }

    private static void stamp(ItemStack stack, String focus) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = new CompoundTag();
            inner.putString("focus", focus);
            tag.put(KEY, inner);
        });
    }

    private static void applyCommon(ItemStack stack, ItemUseAnimation anim, float useCooldownSeconds, String group) {
        stack.set(DataComponents.CONSUMABLE, Consumable.builder()
                .consumeSeconds(3600.0F)
                .animation(anim)
                .hasConsumeParticles(false)
                .build());
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.USE_COOLDOWN, new UseCooldown(useCooldownSeconds,
                Optional.of(Identifier.fromNamespaceAndPath(CobbleBendingMod.MOD_ID, group))));
    }

    private static ItemLore lore(String... lines) {
        List<Component> comps = new ArrayList<>();
        for (String line : lines) {
            comps.add(Component.literal(line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        return new ItemLore(comps);
    }

}
