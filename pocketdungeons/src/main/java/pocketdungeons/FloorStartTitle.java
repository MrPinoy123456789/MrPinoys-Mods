package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The big text when a floor commits (playtest 2026-10-02-1): the floor's name
 * on screen, then each affix stacking under it one at a time, with a low thud
 * for every line. Vanilla shows one title and one subtitle at a time, so
 * "stacking downward" is a subtitle that gains one affix per beat.
 *
 * <p>The beats run off the server tick, not a thread. A player who logs out
 * mid-sequence simply loses the rest of it.
 */
final class FloorStartTitle {

    /** Game ticks between one line and the next. */
    static final int BEAT_TICKS = 15;

    private record Sequence(UUID player, Component title, List<String> affixes, long[] nextTick, int[] shown) {}

    private static final List<Sequence> RUNNING = new ArrayList<>();

    private FloorStartTitle() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(FloorStartTitle::tick);
    }

    /** Starts the sequence for every online member of {@code record}'s party. */
    static void show(MinecraftServer server, InstanceRecord record) {
        if (server == null || record == null) {
            return;
        }
        Component title = Component.literal(DungeonScreen.themeName(record.floor.theme).toUpperCase())
                .withStyle(ChatFormatting.GOLD);
        List<String> affixes = affixLabels(record.floor.affixes);
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player == null) {
                continue;
            }
            RUNNING.removeIf(s -> s.player.equals(member));
            Sequence sequence = new Sequence(member, title, affixes, new long[]{server.getTickCount()}, new int[]{-1});
            RUNNING.add(sequence);
            step(server, sequence);
        }
    }

    private static void tick(MinecraftServer server) {
        if (RUNNING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Sequence> it = RUNNING.iterator();
        List<Sequence> due = new ArrayList<>();
        while (it.hasNext()) {
            Sequence s = it.next();
            if (s.shown[0] >= s.affixes.size()) {
                it.remove();
            } else if (now >= s.nextTick[0]) {
                due.add(s);
            }
        }
        for (Sequence s : due) {
            step(server, s);
        }
    }

    /** Shows the next line: the title first (line 0), then one more affix each beat. */
    private static void step(MinecraftServer server, Sequence s) {
        ServerPlayer player = server.getPlayerList().getPlayer(s.player);
        if (player == null) {
            s.shown[0] = Integer.MAX_VALUE;
            return;
        }
        int shown = ++s.shown[0];
        s.nextTick[0] = server.getTickCount() + BEAT_TICKS;
        Component subtitle = shown == 0 ? Component.empty()
                : Component.literal(String.join("  ", s.affixes.subList(0, shown))).withStyle(ChatFormatting.LIGHT_PURPLE);
        player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 45, 15));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(s.title));
        Chime.thud(player, shown);
    }

    private static List<String> affixLabels(java.util.Collection<String> ids) {
        List<String> labels = new ArrayList<>();
        if (ids == null) {
            return labels;
        }
        for (String id : ids) {
            String label = FloorHistory.words(id);
            for (AffixDefinition def : AffixManifest.current().definitions()) {
                if (def.id.equals(id)) {
                    label = def.label.toUpperCase();
                    break;
                }
            }
            labels.add(label);
        }
        return labels;
    }
}
