package cobblebending;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Per-player sound cues straight down the connection.
 */
public final class Chime {

    private Chime() {}

    public static void chargeMedium(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.15f, 0.8f);
    }

    public static void chargeHeavy(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.2f, 1.1f);
    }

    public static void hurlReleased(ServerPlayer player, float pitch) {
        play(player, SoundEvents.STONE_BREAK, 0.3f, pitch);
    }

    public static void boulderImpact(ServerPlayer player) {
        play(player, SoundEvents.STONE_HIT, 0.4f, 0.7f);
    }

    public static void wallRaised(ServerPlayer player) {
        play(player, SoundEvents.STONE_PLACE, 0.35f, 1.0f);
    }

    public static void wallRecalled(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_HAT, 0.2f, 1.0f);
    }

    public static void outOfCobble(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_DIDGERIDOO, 0.25f, 0.6f);
    }

    @SuppressWarnings("unchecked")
    private static void play(ServerPlayer player, Object sound, float volume, float pitch) {
        Holder<SoundEvent> holder;
        if (sound instanceof Holder<?> h) {
            holder = (Holder<SoundEvent>) h;
        } else {
            holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder((SoundEvent) sound);
        }
        player.connection.send(new ClientboundSoundPacket(
                holder, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }
}
