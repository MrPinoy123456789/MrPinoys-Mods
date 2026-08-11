package ballot.mc;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A single quiet confirmation note, sent straight down a player's connection so only
 * they hear it. Mirrors the {@code Chime} pattern used by {@code wondrous},
 * {@code dailyquests}, {@code bounties}, and {@code cobbleeconomy}.
 */
public final class Chime {

    private Chime() {}

    /** A vote was cast or changed via {@code /ballot _ cast}. */
    public static void voteCast(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_HAT, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, 1.6f, player.getRandom().nextLong()));
    }

    /** A poll closed and results were announced to everyone online. */
    public static void resultsAnnounced(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSoundPacket(
                    SoundEvents.NOTE_BLOCK_BELL, SoundSource.RECORDS,
                    player.getX(), player.getY(), player.getZ(),
                    0.3f, 1.0f, player.getRandom().nextLong()));
        }
    }
}
