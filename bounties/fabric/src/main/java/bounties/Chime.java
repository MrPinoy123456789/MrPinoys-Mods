package bounties;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A single quiet confirmation note, sent straight down the target's connection so
 * only they hear it. Mirrors the pattern used by {@code dailyquests}, {@code wondrous},
 * and {@code cobbleeconomy}.
 */
public final class Chime {

    private Chime() {}

    /** A bounty was accepted via {@code /bounty accept}. */
    public static void accepted(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_BELL, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, 1.0f, player.getRandom().nextLong()));
    }

    /** A kill counted toward a held bounty, but the bounty is not yet complete. */
    public static void progressed(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_BASS, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.15f, 0.8f, player.getRandom().nextLong()));
    }

    /** A held bounty was completed and paid out. */
    public static void completed(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_CHIME, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.4f, 1.6f, player.getRandom().nextLong()));
    }
}
