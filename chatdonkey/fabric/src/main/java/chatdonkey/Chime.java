package chatdonkey;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Quiet per-player confirmation notes, sent straight down the target's
 * connection so only they hear them. The shared suite pattern (DESIGN.md
 * section 4.8).
 *
 * <p>Two sounds are deliberately <em>not</em> here. The donkey's bray is a loud
 * world sound played from the entity, and its speech is {@link Voice}'s
 * animalese -- both are performances rather than confirmations (SPEC.md
 * section 8).
 */
public final class Chime {

    private Chime() {}

    /** The parting gift hit the player's inventory. */
    public static void gift(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.3f, 1.4f);
    }

    /** A diamond changed hands and the donkey is leaving graciously. */
    public static void bribeAccepted(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BELL, 0.3f, 1.0f);
    }

    private static void play(ServerPlayer player,
                             net.minecraft.core.Holder.Reference<net.minecraft.sounds.SoundEvent> sound,
                             float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }
}
