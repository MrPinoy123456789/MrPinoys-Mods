package thingy;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * One quiet note per action, sent straight down the target's connection so only
 * they hear it. No queueing needed, unlike quizengine's {@code Jingles}: every
 * wondrous cue is fired at the moment of the event.
 */
public final class Chime {

    private Chime() {}

    private static void note(ServerPlayer player, Holder<SoundEvent> sound, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                0.3f, pitch, player.getRandom().nextLong()));
    }

    /** A wondrous item landed in someone's inventory via {@code /wondrous give}. */
    public static void itemGiven(ServerPlayer player) {
        note(player, SoundEvents.NOTE_BLOCK_CHIME, 1.4f);
    }

    /** A machine or link was selected. */
    public static void select(ServerPlayer player)  { note(player, SoundEvents.NOTE_BLOCK_BELL,  1.2f); }

    /** An action succeeded. */
    public static void confirm(ServerPlayer player) { note(player, SoundEvents.NOTE_BLOCK_CHIME, 1.4f); }

    /** An action was refused. */
    public static void reject(ServerPlayer player)  { note(player, SoundEvents.NOTE_BLOCK_BASS,  0.8f); }

    /** A link was broken. */
    public static void unlink(ServerPlayer player)  { note(player, SoundEvents.NOTE_BLOCK_BASS,  1.0f); }

    /** The mortar crushed something. */
    public static void crush(ServerPlayer player)   { note(player, Holder.direct(SoundEvents.STONE_BREAK), 1.0f); }

    /** Action bar, not chat. One line, replaced by the next one. */
    public static void say(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text), true);
    }
}
