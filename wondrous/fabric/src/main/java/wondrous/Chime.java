package wondrous;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A single quiet confirmation note, sent straight down the target's connection so
 * only they hear it. No queueing needed -- unlike quizengine's {@code Jingles},
 * every wondrous cue is one note fired at the moment of the event, not a short tune.
 */
public final class Chime {

    private Chime() {}

    /** A wondrous item landed in someone's inventory via {@code /wondrous give}. */
    public static void itemGiven(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_CHIME, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, 1.4f, player.getRandom().nextLong()));
    }
}
