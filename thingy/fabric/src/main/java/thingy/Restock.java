package thingy;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Off-hand ring that refills the main hand from the inventory when a stack
 * empties, matching item and components so enchanted tools stay enchanted.
 */
public final class Restock {

    public static final String ID = "restock_ring";
    private static final String NAMESPACED_ID = ItemRegistry.namespaced(ID);

    private static final Map<UUID, ItemStack> previousMain = new HashMap<>();

    private Restock() {}

    public static void register() {
        Aura.add((player, carried) -> {
            if (!carried.held(NAMESPACED_ID)) {
                return;
            }

            UUID uuid = player.getUUID();
            ItemStack current = player.getMainHandItem();
            ItemStack last = previousMain.get(uuid);

            if (current.isEmpty() && last != null && !last.isEmpty()) {
                restock(player, last);
            }

            previousMain.put(uuid, current.copy());
        });

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> previousMain.remove(handler.player.getUUID()));
    }

    private static void restock(ServerPlayer player, ItemStack wanted) {
        int mainSlot = 27 + player.getInventory().getSelectedSlot();

        for (int i = 0; i < 36; i++) {
            if (i == mainSlot) {
                continue;
            }
            ItemStack candidate = player.getInventory().getItem(i);
            if (candidate.isEmpty()) {
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(candidate, wanted)) {
                continue;
            }

            ItemStack taken;
            if (candidate.getCount() > 1) {
                taken = candidate.split(1);
            } else {
                taken = candidate;
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }

            player.getInventory().setItem(mainSlot, taken);
            player.getInventory().setChanged();
            Chime.say(player, "restocked");
            return;
        }
    }
}
