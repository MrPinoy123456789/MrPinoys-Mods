package chatdonkey;

import chatdonkey.core.Animalese;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Plays {@link Animalese} lines out across server ticks.
 *
 * <p>A chat line's blips land one per tick, so a line takes roughly as long to
 * "say" as it takes to read -- which is the entire trick behind Animal
 * Crossing's dialogue.
 *
 * <p>{@code NOTE_BLOCK_BIT} is the square-wave chiptune voice and is by far the
 * closest vanilla sound to animalese. Each blip goes down each listener's own
 * connection at their own position, per the suite's per-player sound rule
 * (DESIGN.md section 4.8), so the volume is the same for everyone who was sent
 * the line rather than fading out for whoever stood furthest away.
 */
public final class Voice {

    /** Quiet enough to talk over, loud enough to be the point. */
    private static final float VOLUME = 0.25f;

    private final List<Utterance> speaking = new ArrayList<>();

    /**
     * Starts speaking a line to everyone who was sent it.
     *
     * <p>{@code speakerId} identifies the donkey, {@code speakerName} only picks
     * the voice. They are separate on purpose: names are drawn at random from a
     * ten-name pool, so two donkeys running at once can easily both be Duncan,
     * and keying interruption by name would have each one cutting the other off
     * mid-sentence for two unrelated players.
     */
    public void speak(List<ServerPlayer> audience, String line,
                      UUID speakerId, String speakerName) {
        if (audience.isEmpty()) {
            return;
        }
        List<Animalese.Blip> blips = Animalese.speak(line, Animalese.voiceSeed(speakerName));
        if (blips.isEmpty()) {
            return;
        }
        // A new line interrupts whatever THIS donkey was still saying, rather
        // than two lines blipping over each other.
        speaking.removeIf(u -> u.speakerId.equals(speakerId));
        speaking.add(new Utterance(new ArrayList<>(audience), blips, speakerId));
    }

    /** Called once per server tick. */
    public void tick() {
        if (speaking.isEmpty()) {
            return;
        }
        Iterator<Utterance> it = speaking.iterator();
        while (it.hasNext()) {
            if (it.next().tick()) {
                it.remove();
            }
        }
    }

    /** Drops anything still being said, e.g. when the event ends. */
    public void silence(UUID speakerId) {
        speaking.removeIf(u -> u.speakerId.equals(speakerId));
    }

    public void clear() {
        speaking.clear();
    }

    private static final class Utterance {
        private final List<ServerPlayer> audience;
        private final List<Animalese.Blip> blips;
        private final UUID speakerId;
        private int elapsed;
        private int next;

        Utterance(List<ServerPlayer> audience, List<Animalese.Blip> blips, UUID speakerId) {
            this.audience = audience;
            this.blips = blips;
            this.speakerId = speakerId;
        }

        /** @return true once this line has finished being said */
        boolean tick() {
            while (next < blips.size() && blips.get(next).tickOffset() <= elapsed) {
                play(blips.get(next).pitch());
                next++;
            }
            elapsed++;
            return next >= blips.size();
        }

        private void play(float pitch) {
            for (ServerPlayer listener : audience) {
                if (listener.isRemoved() || listener.hasDisconnected()) {
                    continue;
                }
                listener.connection.send(new ClientboundSoundPacket(
                        SoundEvents.NOTE_BLOCK_BIT, SoundSource.RECORDS,
                        listener.getX(), listener.getY(), listener.getZ(),
                        VOLUME, pitch, listener.getRandom().nextLong()));
            }
        }
    }
}
