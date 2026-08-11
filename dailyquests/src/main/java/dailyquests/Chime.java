package dailyquests;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A single quiet confirmation note, sent straight down the target's connection so
 * only they hear it.
 */
public final class Chime {

    private Chime() {}

    /** A daily quest was successfully turned in. */
    public static void questTurnedIn(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_BELL, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, 1.2f, player.getRandom().nextLong()));
    }
}
