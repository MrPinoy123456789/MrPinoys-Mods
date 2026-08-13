package cobbleeconomy;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A single quiet confirmation note, sent straight down the target's connection so
 * only they hear it. Mirrors the pattern used by {@code dailyquests} and
 * {@code wondrous}.
 */
public final class Chime {

    private Chime() {}

    /** A shop purchase completed successfully. */
    public static void purchased(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_HARP, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, 1.2f, player.getRandom().nextLong()));
    }

    /** The first-join welcome was shown. */
    public static void welcome(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_CHIME, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, 1.0f, player.getRandom().nextLong()));
    }

    /** Currency was received via {@code /pay}. */
    public static void paymentReceived(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_CHIME, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, 1.0f, player.getRandom().nextLong()));
    }
}
