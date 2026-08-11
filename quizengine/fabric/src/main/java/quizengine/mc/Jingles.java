package quizengine.mc;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Note block jingles, played per-player.
 *
 * <p>Sounds go out as a sound packet down a single player's connection, so a correct
 * answer chimes for the person who got it right and nobody else — the same privacy
 * property the clickable options have.
 *
 * <p>Note that {@code Player.playSound} would do the opposite: it excludes the player
 * it is called on, because a vanilla client plays its own sounds locally. Sending the
 * packet directly is what makes this per-player rather than everyone-but.
 *
 * <p>Notes are queued against absolute server ticks and drained from the orchestrator's
 * existing tick loop. No threads, no timers, no scheduling machinery.
 */
public final class Jingles {

    private Jingles() {}

    /**
     * One note. {@code semitone} runs 0–24, matching a note block's 25 pitches, where
     * 12 is the middle. {@code delayTicks} is measured from the start of the jingle,
     * so a tune is written as absolute offsets rather than gaps.
     */
    public record Note(Holder<SoundEvent> instrument, int semitone, int delayTicks) {}

    public record Jingle(List<Note> notes, float volume) {
        public Jingle {
            notes = List.copyOf(notes);
        }

        public Jingle(List<Note> notes) {
            this(notes, 0.4f);
        }
    }

    private record Pending(UUID player, Holder<SoundEvent> instrument, float pitch, float volume, int atTick) {}

    private static final List<Pending> QUEUE = new ArrayList<>();

    // ---- instruments ------------------------------------------------------

    private static Holder<SoundEvent> harp() {
        return SoundEvents.NOTE_BLOCK_HARP;
    }

    private static Holder<SoundEvent> bell() {
        return SoundEvents.NOTE_BLOCK_BELL;
    }

    private static Holder<SoundEvent> bass() {
        return SoundEvents.NOTE_BLOCK_BASS;
    }

    private static Holder<SoundEvent> pling() {
        return SoundEvents.NOTE_BLOCK_PLING;
    }

    private static Holder<SoundEvent> chime() {
        return SoundEvents.NOTE_BLOCK_CHIME;
    }

    // ---- tunes ------------------------------------------------------------

    /** A round is starting. Rising, attention-getting, not triumphant. */
    public static Jingle roundStart() {
        return new Jingle(List.of(
                new Note(harp(), 12, 0),
                new Note(harp(), 16, 3),
                new Note(harp(), 19, 6),
                new Note(bell(), 24, 10)), 0.35f);
    }

    /** Your answer was accepted. Deliberately tiny — this fires a lot. */
    public static Jingle accepted() {
        return new Jingle(List.of(
                new Note(pling(), 16, 0),
                new Note(pling(), 20, 2)), 0.2f);
    }

    /** Voting is open. Two notes, a question rather than a statement. */
    public static Jingle votingOpen() {
        return new Jingle(List.of(
                new Note(chime(), 14, 0),
                new Note(chime(), 21, 4)), 0.3f);
    }

    /** You were right, or you backed the winner. Major arpeggio. */
    public static Jingle correct() {
        return new Jingle(List.of(
                new Note(bell(), 12, 0),
                new Note(bell(), 16, 3),
                new Note(bell(), 19, 6),
                new Note(bell(), 24, 9)), 0.35f);
    }

    /** You were wrong. Short, low, and over quickly — no need to rub it in. */
    public static Jingle wrong() {
        return new Jingle(List.of(
                new Note(bass(), 10, 0),
                new Note(bass(), 6, 4)), 0.25f);
    }

    /** You wrote the winning answer. The only tune that gets to show off. */
    public static Jingle winner() {
        return new Jingle(List.of(
                new Note(bell(), 12, 0),
                new Note(bell(), 12, 3),
                new Note(bell(), 19, 6),
                new Note(chime(), 24, 10),
                new Note(chime(), 21, 14),
                new Note(chime(), 24, 17)), 0.4f);
    }

    /** Something was rejected. One flat note, easy to ignore. */
    public static Jingle rejected() {
        return new Jingle(List.of(new Note(bass(), 8, 0)), 0.2f);
    }

    // ---- playback ---------------------------------------------------------

    public static void play(ServerPlayer player, Jingle jingle) {
        int now = player.level().getServer().getTickCount();
        for (Note note : jingle.notes()) {
            QUEUE.add(new Pending(player.getUUID(), note.instrument(),
                    pitchOf(note.semitone()), jingle.volume(), now + note.delayTicks()));
        }
    }

    public static void playToAll(MinecraftServer server, Jingle jingle) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            play(player, jingle);
        }
    }

    /** Drained once per tick by the orchestrator. */
    public static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        Iterator<Pending> it = QUEUE.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.atTick() > now) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(p.player());
            if (player != null) {
                player.connection.send(new ClientboundSoundPacket(
                        p.instrument(), SoundSource.RECORDS,
                        player.getX(), player.getY(), player.getZ(),
                        p.volume(), p.pitch(), player.getRandom().nextLong()));
            }
            it.remove();
        }
    }

    public static void clear() {
        QUEUE.clear();
    }

    /** Note blocks span two octaves across 25 steps, with 12 as the centre. */
    private static float pitchOf(int semitone) {
        int clamped = Math.max(0, Math.min(24, semitone));
        return (float) Math.pow(2.0, (clamped - 12) / 12.0);
    }
}