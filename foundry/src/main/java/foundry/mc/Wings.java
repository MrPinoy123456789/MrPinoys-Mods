package foundry.mc;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gliding with wings worn in a Trinkets slot. Trinkets Updated already treats a glider item in a trinket slot as a glider
 * on the server, but vanilla's client only sends "start gliding" when its own chest slot holds one, so a player who holds
 * jump while falling is started here, as vanilla's jump to glide would. Wings are race locked: a player who wears them
 * without the permission is stopped if they glide. Worn wings never wear out (no durability). Worked out from the
 * sources and the mod's own documentation, not tried on a live client.
 */
public final class Wings {
    private static final Set<UUID> WINGED = new HashSet<>();
    private static final Set<UUID> DENIED = new HashSet<>();

    private Wings() {}

    /** True when this player wears allowed wings in a trinket slot (refreshed every {@value WornDisplays#SCAN_EVERY} ticks). */
    public static boolean isWinged(UUID player) {
        return WINGED.contains(player);
    }

    /** True when this player wears wings they may not use. */
    public static boolean isDenied(UUID player) {
        return DENIED.contains(player);
    }

    static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % WornDisplays.SCAN_EVERY == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                boolean[] found = {false, false};
                if (WornItems.trinkets() && player.isAlive()) {
                    TrinketsHook.worn(player, stack -> {
                        if (stack.getItem() instanceof FoundryItem item && item.def() != null && item.def().glider()) {
                            found[Gate.allowed(player, item.def()) ? 0 : 1] = true;
                        }
                    });
                }
                set(WINGED, player.getUUID(), found[0]);
                set(DENIED, player.getUUID(), found[1] && !found[0]);
            }
        }
        if (WINGED.isEmpty() && DENIED.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (DENIED.contains(id) && player.isFallFlying()) {
                player.stopFallFlying();
                player.sendSystemMessage(Component.literal("Only the Fairies of Everdelle can glide with these wings."), true);
                continue;
            }
            if (!WINGED.contains(id) || player.isFallFlying() || player.onGround() || player.isInWater()
                    || player.isPassenger() || player.getAbilities().flying) continue;
            if (player.getLastClientInput().jump() && player.getDeltaMovement().y < 0) player.startFallFlying();
        }
    }

    private static void set(Set<UUID> set, UUID id, boolean on) {
        if (on) set.add(id);
        else set.remove(id);
    }

    static void forget(UUID player) {
        WINGED.remove(player);
        DENIED.remove(player);
    }

    static void clear() {
        WINGED.clear();
        DENIED.clear();
    }
}
