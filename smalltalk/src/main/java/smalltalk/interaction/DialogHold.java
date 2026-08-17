package smalltalk.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a villager holding still and facing the player for as long as the
 * Small Talk dialogue menu might still be open -- the same
 * {@code setTradingPlayer} freeze vanilla uses for real trading. Dialog
 * screens are a one-shot payload with no server-side "the player closed
 * this" signal (unlike a tracked container menu), so the freeze is
 * refreshed on every button press and otherwise expires on its own after
 * {@link #HOLD_TICKS}.
 */
public final class DialogHold {

    private static final long HOLD_TICKS = 15 * 20L;

    private static final Map<Villager, Long> expiryTick = new ConcurrentHashMap<>();
    private static long tick = 0;

    private DialogHold() {}

    /** Called whenever the dialogue menu or gift picker opens, or a button inside one is pressed. */
    public static void hold(Villager villager, ServerPlayer player) {
        villager.setTradingPlayer(player);
        expiryTick.put(villager, tick + HOLD_TICKS);
    }

    /** Called when a flow that manages its own freeze (real trading) takes over. */
    public static void release(Villager villager) {
        expiryTick.remove(villager);
    }

    public static void serverTick() {
        tick++;
        Iterator<Map.Entry<Villager, Long>> it = expiryTick.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Villager, Long> entry = it.next();
            Villager villager = entry.getKey();
            if (tick >= entry.getValue() || !villager.isAlive()) {
                villager.setTradingPlayer(null);
                it.remove();
            }
        }
    }
}
