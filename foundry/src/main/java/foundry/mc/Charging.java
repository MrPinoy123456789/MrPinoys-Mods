package foundry.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hold-to-charge state: which charged mechanic a player armed when they started holding right click, and when. A hold
 * is decided at release, like a bow. The mechanic is remembered from the start, so letting go of sneak halfway does not
 * change what fires. Server thread only.
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
    }

    static void clear() {
        HOLDS.clear();
    }

    static int live() {
        return HOLDS.size();
    }
}
