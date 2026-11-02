package charter.mc.book;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Feedback outside the book, for a player who mostly cannot see chat: a line on the action bar and a sound only they
 * hear. Chat keeps its copy for Java players; these come first. Sounds go to the one player as a packet, since a
 * server side {@code playSound} from a player is heard by everyone near them except that player.
 */
public final class Cues {

    private Cues() {
    }

    /** A short line above the hotbar: green for done, red for refused, white for a prompt. */
    public static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.sendOverlayMessage(Component.literal(text).withStyle(colour));
    }

    /** Something now needs the player: a soft chime. */
    public static void chime(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.6F, 1.2F);
    }

    /** A press in the book had a result. */
    public static void click(ServerPlayer player) {
        play(player, SoundEvents.UI_BUTTON_CLICK, 0.4F, 1.0F);
    }

    /** A journey ended where it was going. */
    public static void arrive(ServerPlayer player) {
        play(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ENDERMAN_TELEPORT), 0.5F, 1.0F);
    }

    private static void play(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }
}
