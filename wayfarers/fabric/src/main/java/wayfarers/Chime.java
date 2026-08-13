package wayfarers;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A single quiet confirmation note, copied from dailyquests.
 *
 * <p>Unused in M2, but the sound hook belongs with the mod from the start.
 */
public final class Chime {

    private Chime() {}

    public static void play(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_BELL, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, 1.2f, player.getRandom().nextLong()));
    }
}
