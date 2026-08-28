package thingy;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import thingy.api.VirtualTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One shared per-player tick loop. Every 10 ticks it builds a {@link Carried}
 * snapshot and runs every registered {@link Pass} against it.
 *
 * <p>Promoted to a framework service (PLAN.md Phase 1): a wondrous-only
 * {@code Aura} kept bare ids because there was only one namespace to check.
 * This one is namespaced: {@code "wondrous:flying_boots"}, not
 * {@code "flying_boots"}, because a future namespace (kamutotems,
 * spiritwolves, Phase 5) has to be able to register its own passive effects
 * on the same shared poll without a bare id from one namespace colliding with
 * another's.
 */
public final class Aura {

    private static final int POLL_INTERVAL = 10;

    private static final List<Pass> passes = new ArrayList<>();

    private Aura() {}

    /** What one player is carrying, and where. Built once per player per pass. */
    public record Carried(Map<String, EquipmentSlot> equipped, Set<String> inInventory) {

        public boolean wornAt(String namespacedId, EquipmentSlot slot) {
            return equipped.get(namespacedId) == slot;
        }

        public boolean held(String namespacedId) {
            EquipmentSlot slot = equipped.get(namespacedId);
            return slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND;
        }

        public boolean anywhere(String namespacedId) {
            return equipped.containsKey(namespacedId) || inInventory.contains(namespacedId);
        }
    }

    public interface Pass {
        void apply(ServerPlayer player, Carried carried);
    }

    /** Registers the shared polling loop that evaluates all aura passes for online players. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % POLL_INTERVAL != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Carried carried = buildCarried(player);
                for (Pass pass : passes) {
                    pass.apply(player, carried);
                }
            }
        });
    }

    /** Adds a passive effect evaluator to the shared aura poll. */
    public static void add(Pass pass) {
        passes.add(pass);
    }

    public static Carried forPlayer(ServerPlayer player) {
        return buildCarried(player);
    }

    private static Carried buildCarried(ServerPlayer player) {
        Map<String, EquipmentSlot> equipped = new HashMap<>();
        Set<String> inInventory = new HashSet<>();

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            VirtualTag.readAny(stack).ifPresent(id -> equipped.put(id, slot));
        }

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            VirtualTag.readAny(player.getInventory().getItem(i)).ifPresent(inInventory::add);
        }

        return new Carried(equipped, inInventory);
    }
}
