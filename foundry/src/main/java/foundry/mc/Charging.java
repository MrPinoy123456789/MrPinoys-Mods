package foundry.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hold-to-charge state: which charged mechanic a player armed when they started holding right click, and when. A hold
 * is decided at release, like a bow. The mechanic is remembered from the start, so letting go of sneak halfway does not
 * change what fires. Server thread only.
 *
 * <p>Bedrock players cannot hold: their client never tells the server it let go of an item Bedrock does not see as
 * usable, so a hold would never end. For them a tap starts a cast instead, which fires by itself after the same charge
 * time while the item is still in the same hand, so the wait (and the balance) is the same for both editions.
 */
final class Charging {
    record Hold(String contentId, int index, long startTick, int needTicks) {}

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private Charging() {}

    static void begin(ServerPlayer player, Hold hold) {
        HOLDS.put(player.getUUID(), hold);
    }

    /** Takes the player's hold, or null when they have none. */
    static Hold end(ServerPlayer player) {
        return HOLDS.remove(player.getUUID());
    }

    static void forget(UUID player) {
        HOLDS.remove(player);
        CASTS.remove(player);
    }

    static void clear() {
        HOLDS.clear();
        CASTS.clear();
    }

    /** A Bedrock cast: the armed mechanic fires at {@code fireTick} if the item is still in {@code hand}. */
    record Cast(String contentId, int index, long fireTick, net.minecraft.world.InteractionHand hand) {}

    private static final Map<UUID, Cast> CASTS = new HashMap<>();

    static void cast(ServerPlayer player, Cast cast) {
        CASTS.put(player.getUUID(), cast);
    }

    /** True while this player has a cast waiting to fire. */
    static boolean casting(UUID player) {
        return CASTS.containsKey(player);
    }

    /**
     * Fires every cast that is due. A cast whose item left the hand (switched, dropped) or whose player died fizzles,
     * as a drawn bow does when the player swaps away.
     */
    static void tick(net.minecraft.server.MinecraftServer server) {
        if (CASTS.isEmpty()) return;
        long now = server.getTickCount();
        var it = CASTS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Cast cast = e.getValue();
            if (now < cast.fireTick()) continue;
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null || !player.isAlive()) continue;
            if (player.getItemInHand(cast.hand()).getItem() instanceof FoundryItem item && item.contentId().equals(cast.contentId())) {
                item.fireCast(player, cast.index());
            } else {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("The spell fizzled"), true);
            }
        }
    }

    static int live() {
        return HOLDS.size() + CASTS.size();
    }
}
