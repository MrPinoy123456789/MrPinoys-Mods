package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * M26's "reads it out" touch: the moment a player picks up a diary book,
 * its pages scroll across the action bar the way Hearsay's villagers speak
 * over the same line -- a beat that reads as atmosphere, not as another
 * screen to close. The book itself is unaffected; this is a second,
 * ambient view of the same text {@link DiaryDelivery} already handed over.
 *
 * <p>One read at a time per player: starting a new one (finding a second
 * diary before the first finishes scrolling, unlikely but possible on a
 * multi-level jump) simply replaces whatever was scrolling.
 */
final class DiaryReading {

    private DiaryReading() {}

    /** How many characters of the marquee are visible above the XP bar at once. */
    private static final int WINDOW = 40;
    /** How many ticks pass between each one-character step -- roughly 20 characters/second. */
    private static final int TICKS_PER_STEP = 1;

    private record ActiveRead(String padded, int position) {}

    private static final Map<UUID, ActiveRead> reading = new HashMap<>();
    private static int tickCounter;

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(DiaryReading::onTick);
    }

    /** Starts scrolling {@code diary}'s pages, in their canonical order, across {@code player}'s action bar. */
    static void start(ServerPlayer player, Diaries.Entry diary) {
        String joined = String.join("     ", diary.pages()).replace('\n', ' ')
                .replaceAll(" {2,}", "   ");
        String padding = " ".repeat(WINDOW);
        reading.put(player.getUUID(), new ActiveRead(padding + joined + padding, 0));
    }

    private static void onTick(MinecraftServer server) {
        if (reading.isEmpty()) {
            return;
        }
        if (++tickCounter % TICKS_PER_STEP != 0) {
            return;
        }
        Iterator<Map.Entry<UUID, ActiveRead>> it = reading.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveRead> mapEntry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(mapEntry.getKey());
            ActiveRead read = mapEntry.getValue();
            if (player == null || read.position() + WINDOW > read.padded().length()) {
                it.remove();
                continue;
            }
            player.sendSystemMessage(Component.literal(
                    read.padded().substring(read.position(), read.position() + WINDOW)), true);
            mapEntry.setValue(new ActiveRead(read.padded(), read.position() + 1));
        }
    }
}
