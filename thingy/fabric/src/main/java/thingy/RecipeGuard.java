package thingy;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.FuelValues;
import thingy.api.VirtualTag;

import java.util.HashSet;
import java.util.Set;

/**
 * Stops a tagged item from being crafted into its ingredients, and marks fuel
 * items so players know not to burn them.
 *
 * <p>Per-namespace opt-in at registration (PLAN.md Phase 1): a namespace must
 * call {@link #optIn} before its items are protected. There is no "any tagged
 * item" default, because that would silently make a kamutotems totem or a
 * spiritwolves stone uncraftable as an ingredient the moment those mods
 * migrate onto Thingy, without either mod choosing that. {@code wondrous}
 * opts in below, reproducing the guard's original behaviour exactly.
 */
public final class RecipeGuard {

    private static final Set<String> OPTED_IN = new HashSet<>();

    private static FuelValues fuelValues;

    private RecipeGuard() {}

    public static void register() {
        optIn("wondrous");

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!(player.containerMenu instanceof AbstractCraftingMenu menu)) {
                    continue;
                }
                boolean tagged = false;
                for (Slot slot : menu.getInputGridSlots()) {
                    if (isGuarded(slot.getItem())) {
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

    /** Enrolls a namespace's items in the crafting guard. Call once, at that namespace's registration time. */
    public static void optIn(String namespace) {
        OPTED_IN.add(namespace);
    }

    private static boolean isGuarded(ItemStack stack) {
        for (String namespace : OPTED_IN) {
            if (VirtualTag.read(stack, namespace).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Cached once the server is available, because vanilla fuel values depend on data. */
    public static boolean isFuel(Item base) {
        return fuelValues != null && fuelValues.isFuel(new ItemStack(base));
    }
}
