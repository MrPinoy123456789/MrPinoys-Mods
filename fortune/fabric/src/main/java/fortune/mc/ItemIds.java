package fortune.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/** Item ids from the config turned into items, and the named keepsakes. */
final class ItemIds {

    private ItemIds() {
    }

    /** The item for an id, or {@code fallback} when the id is malformed or not registered (Foundry content absent). */
    static Item item(String id, Item fallback) {
        Identifier key = id == null ? null : Identifier.tryParse(id);
        if (key == null) return fallback;
        return BuiltInRegistries.ITEM.getOptional(key).orElse(fallback);
    }

    /** A real, givable stack. Unknown ids give nothing. */
    static ItemStack stack(String id, int count) {
        Item item = item(id, Items.AIR);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    /** A named, glinting keepsake: the jackpot charm or a milestone charm. */
    static ItemStack keepsake(String id, String name, List<String> lore) {
        ItemStack stack = stack(id, 1);
        if (stack.isEmpty()) stack = new ItemStack(Items.CLOCK);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.LORE, new ItemLore(lore.stream()
                .map(line -> (Component) Component.literal(line).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))
                .toList()));
        return stack;
    }
}
