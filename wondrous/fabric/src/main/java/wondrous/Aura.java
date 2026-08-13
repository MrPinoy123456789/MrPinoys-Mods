package wondrous;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import wondrous.api.WondrousTag;

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
 */
public final class Aura {

    private static final int POLL_INTERVAL = 10;

    private static final List<Pass> passes = new ArrayList<>();

    private Aura() {}

    /** What one player is carrying, and where. Built once per player per pass. */
    public record Carried(Map<String, EquipmentSlot> equipped, Set<String> inInventory) {

        public boolean wornAt(String id, EquipmentSlot slot) {
            return equipped.get(id) == slot;
        }

        public boolean held(String id) {
            EquipmentSlot slot = equipped.get(id);
            return slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND;
        }

        public boolean anywhere(String id) {
            return equipped.containsKey(id) || inInventory.contains(id);
        }
    }

    public interface Pass {
        void apply(ServerPlayer player, Carried carried);
    }

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
            WondrousTag.read(stack).ifPresent(id -> equipped.put(id, slot));
        }

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            WondrousTag.read(player.getInventory().getItem(i)).ifPresent(inInventory::add);
        }

        return new Carried(equipped, inInventory);
    }
}
