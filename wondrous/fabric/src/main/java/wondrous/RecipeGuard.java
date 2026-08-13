package wondrous;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.FuelValues;
import wondrous.api.WondrousTag;

/**
 * Stops a tagged item from being crafted into its ingredients, and marks fuel
 * items so players know not to burn them.
 */
public final class RecipeGuard {

    private static FuelValues fuelValues;

    private RecipeGuard() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!(player.containerMenu instanceof AbstractCraftingMenu menu)) {
                    continue;
                }
                boolean tagged = false;
                for (Slot slot : menu.getInputGridSlots()) {
                    if (WondrousTag.read(slot.getItem()).isPresent()) {
                        tagged = true;
                        break;
                    }
                }
                if (tagged) {
                    menu.getResultSlot().set(ItemStack.EMPTY);
                    menu.getResultSlot().setChanged();
                }
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                fuelValues = server.fuelValues());
    }

    /** Cached once the server is available, because vanilla fuel values depend on data. */
    public static boolean isFuel(Item base) {
        return fuelValues != null && fuelValues.isFuel(new ItemStack(base));
    }
}
