package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
 * Every piece of big on-screen text goes through here (playtest 2026-10-03:
 * "generalize the room name and affix reveal ... so that anything that uses
 * that big text has the same staggered reveal"). The title lands first, then
 * each subtitle line stacks under it one beat at a time, with a low thud for
 * every beat. Vanilla shows one title and one subtitle at a time, so "stacking
 * downward" is a subtitle that gains one line per beat.
 *
 * <p>Callers: the floor start (the floor's name, then each affix), the way
 * home (HOME, then the key line) and the floor clear (the count, then the
 * choice). A title with no extra lines is a single beat.
 *
 * <p>The beats run off the server tick, not a thread. A player who logs out
 * mid-sequence simply loses the rest of it, and a stopping server drops every
 * sequence.
 */
final class StaggeredTitle {

    /**
     * Game ticks between one line and the next. Playtest 2026-10-02-2 (L14): a
     * slightly longer pause between lines than the original 15.
     */
    static final int BEAT_TICKS = 20;

    /** Fade-in of the first beat, so a lone title still eases in as it used to. */
    private static final int FIRST_FADE_IN = 10;
    /** How long a beat's text stays if no later beat replaces it. */
    private static final int STAY_TICKS = 50;
    private static final int FADE_OUT = 15;

    private static final class Sequence {
        final UUID player;
        final Component title;
        final List<String> lines;
        final ChatFormatting lineStyle;
        long nextTick;
        int shown = -1;
        /** How long the last beat's text stays; a milestone holds longer than the 50 ticks of a floor start. */
        int stay = STAY_TICKS;

        Sequence(UUID player, Component title, List<String> lines, ChatFormatting lineStyle, long nextTick) {
            this.player = player;
            this.title = title;
            this.lines = lines;
            this.lineStyle = lineStyle;
            this.nextTick = nextTick;
        }

        boolean finished() {
            return shown >= lines.size();
        }
    }

    private static final List<Sequence> RUNNING = new ArrayList<>();

    /** A sequence waiting for its moment (PD-165): a milestone queued behind the floor clear's own title. */
    private record Pending(long dueTick, UUID player, Component title, List<String> lines,
                           ChatFormatting lineStyle, int stay, java.util.function.Consumer<ServerPlayer> onShow) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    /**
     * Ticks a milestone waits before it shows. The floor clear and the way home
     * each run their own sequence ({@link RunLifecycle}) in the same tick, and
     * a new sequence replaces a running one, so a milestone shown at once was
     * overwritten before it could be read (PD-165). The clear's title takes about
     * 85 ticks (fade in, the subtitle beat, the stay and the fade out).
     */
    static final int MILESTONE_DELAY_TICKS = 100;
    /** How long a milestone's last line stays: four and a half seconds, nearly twice a floor start's. */
    static final int MILESTONE_STAY_TICKS = 90;

    private StaggeredTitle() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(StaggeredTitle::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RUNNING.clear();
            PENDING.clear();
        });
    }

    /**
     * Queues a milestone: after {@link #MILESTONE_DELAY_TICKS} it shows like
     * {@link #show} but holds {@link #MILESTONE_STAY_TICKS}, and {@code onShow}
     * runs with the player at that moment (the fanfare). A player who left in
     * between simply misses it.
     */
    static void showMilestone(MinecraftServer server, UUID player, Component title, List<String> lines,
                              ChatFormatting lineStyle, java.util.function.Consumer<ServerPlayer> onShow) {
        if (server == null || player == null) {
            return;
        }
        PENDING.add(new Pending(server.getTickCount() + MILESTONE_DELAY_TICKS, player, title,
                List.copyOf(lines), lineStyle, MILESTONE_STAY_TICKS, onShow));
    }

    /** How many milestones are queued for {@code player}, for tests. */
    static int pendingFor(UUID player) {
        return (int) PENDING.stream().filter(p -> p.player().equals(player)).count();
    }

    /**
     * Starts a sequence for {@code player}, replacing one already running for
     * them. The title is shown at once; {@code lines} then join the subtitle
     * one per beat.
     */
    static void show(MinecraftServer server, UUID player, Component title, List<String> lines,
                     ChatFormatting lineStyle) {
        show(server, player, title, lines, lineStyle, STAY_TICKS);
    }

    private static void show(MinecraftServer server, UUID player, Component title, List<String> lines,
                             ChatFormatting lineStyle, int stay) {
        if (server == null || player == null) {
            return;
        }
        RUNNING.removeIf(s -> s.player.equals(player));
        Sequence sequence = new Sequence(player, title, List.copyOf(lines), lineStyle, server.getTickCount());
        sequence.stay = stay;
        RUNNING.add(sequence);
        step(server, sequence);
        if (sequence.finished()) {
            RUNNING.remove(sequence);
        }
    }

    /** The subtitle after {@code shown} lines: the first {@code shown} of {@code lines}, two spaces apart. */
    static String subtitleAt(List<String> lines, int shown) {
        if (shown <= 0) {
            return "";
        }
        return String.join("  ", lines.subList(0, Math.min(shown, lines.size())));
    }

    /** Whether {@code player} has a sequence in flight, for tests. */
    static boolean isRunning(UUID player) {
        return RUNNING.stream().anyMatch(s -> s.player.equals(player));
    }

    private static void tick(MinecraftServer server) {
        if (!PENDING.isEmpty()) {
            long due = server.getTickCount();
            for (Pending pending : new ArrayList<>(PENDING)) {
                if (due < pending.dueTick()) {
                    continue;
                }
                PENDING.remove(pending);
                ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
                if (player == null) {
                    continue;
                }
                show(server, pending.player(), pending.title(), pending.lines(), pending.lineStyle(),
                        pending.stay());
                pending.onShow().accept(player);
            }
        }
        if (RUNNING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<Sequence> due = new ArrayList<>();
        Iterator<Sequence> it = RUNNING.iterator();
        while (it.hasNext()) {
            Sequence s = it.next();
            if (s.finished()) {
                it.remove();
            } else if (now >= s.nextTick) {
                due.add(s);
            }
        }
        for (Sequence s : due) {
            step(server, s);
        }
    }

    /** Shows the next beat: the title alone (beat 0), then one more subtitle line each beat. */
    private static void step(MinecraftServer server, Sequence s) {
        ServerPlayer player = server.getPlayerList().getPlayer(s.player);
        if (player == null) {
            s.shown = Integer.MAX_VALUE;
            return;
        }
        int shown = ++s.shown;
        s.nextTick = server.getTickCount() + BEAT_TICKS;
        Component subtitle = shown == 0 ? Component.empty()
                : Component.literal(subtitleAt(s.lines, shown)).withStyle(s.lineStyle);
        // Each beat resends the title, so only the first one fades in; the rest cut straight to the
        // longer text rather than flickering.
        player.connection.send(new ClientboundSetTitlesAnimationPacket(shown == 0 ? FIRST_FADE_IN : 0,
                s.stay, FADE_OUT));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(s.title));
        Chime.thud(player, shown);
    }
}
