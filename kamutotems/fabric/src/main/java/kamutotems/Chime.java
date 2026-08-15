package kamutotems;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Per-player sound cues, sent straight down the target's connection so only
 * they hear it. Matches the suite's {@code Chime.java} pattern.
 *
 * <p>These are confirmations, not fanfares -- volume 0.15 to 0.4. The single
 * deliberate exception is the totem save (SPEC.md section 12): a quiet save
 * reads as the mod being broken.
 *
 * <p>Two overloads exist on purpose. In 26.2 {@code SoundEvents} is not a
 * uniform type: note-block entries are {@code Holder.Reference<SoundEvent>}
 * while others (TOTEM_USE among them) are bare {@code SoundEvent}. Callers
 * should not have to know which, so both are accepted.
 */
public final class Chime {

    private Chime() {}

    public static void play(ServerPlayer player, Holder<SoundEvent> sound,
                            float volume, float pitch) {
        if (player == null || sound == null) {
            return;
        }
        player.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }

    /** Bare-{@code SoundEvent} overload; wraps it in a direct Holder. */
    public static void play(ServerPlayer player, SoundEvent sound,
                            float volume, float pitch) {
        if (sound == null) {
            return;
        }
        play(player, Holder.direct(sound), volume, pitch);
    }
}
