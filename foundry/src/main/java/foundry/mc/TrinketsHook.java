package foundry.mc;

import eu.pb4.trinkets.api.TrinketsApi;
import eu.pb4.trinkets.api.component.TrinketDataComponents;
import eu.pb4.trinkets.api.component.TrinketEquippable;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Everything that touches the Trinkets mod (Patbox's "Trinkets Updated", mod id trinkets_updated). Only {@link WornItems}
 * reaches this class, and only when that mod is loaded, so Foundry still starts without it.
 */
final class TrinketsHook {
    private TrinketsHook() {}

    /** Lets the item sit in these slots (group/name, such as chest/cape). */
    static void equippable(Item.Properties props, List<String> slots) {
        props.component(TrinketDataComponents.EQUIPMENT, TrinketEquippable.DEFAULT.withSlots(slots.toArray(String[]::new)));
    }

    /** Empties every trinket slot of the player and says how many items were removed. */
    static int clearAll(ServerPlayer player) {
        int[] cleared = {0};
        TrinketsApi.getAttachment(player).forEach((access, stack) -> {
            if (!stack.isEmpty() && access.set(ItemStack.EMPTY)) cleared[0]++;
        });
        return cleared[0];
    }

    /** Every non-empty worn stack whose slot applies its effects. */
    static void worn(ServerPlayer player, Consumer<ItemStack> out) {
        TrinketsApi.getAttachment(player).forEach((access, stack) -> {
            if (!stack.isEmpty() && access.canApplyEffects()) out.accept(stack);
        });
    }
}
