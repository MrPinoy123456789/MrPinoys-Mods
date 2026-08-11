package spiritwolves;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Per-player sound cues, sent straight down the target's connection so only
 * they hear it. Matches the suite's {@code Chime.java} pattern.
 */
public final class Chime {

    private Chime() {}

    /** A wolf was bound to a stone. */
    public static void bound(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BELL, 0.3f, 1.0f);
    }

    /** A wolf was summoned from its stone. */
    public static void summoned(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.3f, 1.0f);
    }

    /** A wolf was recalled into its stone. */
    public static void recalled(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_HAT, 0.2f, 1.0f);
    }

    /** Death was cancelled and a charge was burned. Should feel like a near miss. */
    public static void deathSaved(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.4f, 0.6f);
    }

    /** The wolf's killstreak crossed into a new tier. Rising pitch, quiet. */
    public static void streakUp(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.25f, 1.4f);
    }

    /** The wolf brought back drops from a kill. Quiet -- this happens often. */
    public static void fetched(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_HAT, 0.15f, 1.5f);
    }

    /** A soul was gained from a kill. Quietest of the cues -- this fires on every kill. */
    public static void soulGained(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.12f, 1.0f);
    }

    /** The last charge was just consumed. */
    public static void lastCharge(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_DIDGERIDOO, 0.4f, 1.0f);
    }

    /** The wolf crossed a soul level threshold (SPEC.md section 18.1). */
    public static void levelUp(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.4f, 0.8f);
    }

    /** A verb was unlocked or reached a new tier (SPEC.md section 18.2). */
    public static void verbTierUp(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BELL, 0.35f, 1.2f);
    }

    /** Diamonds were spent to attune a verb tier. */
    public static void verbAttuned(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.3f, 0.7f);
    }

    private static void play(ServerPlayer player, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
                              float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }
}
