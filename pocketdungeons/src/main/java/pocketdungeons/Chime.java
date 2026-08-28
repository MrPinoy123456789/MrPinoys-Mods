package pocketdungeons;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * One quiet note per interaction, sent straight down the player's connection so
 * only they hear it. Matches the suite's {@code Chime.java} pattern; the chime
 * is a side effect, never a gate. All vanilla sound events, no custom assets.
 */
public final class Chime {

    private Chime() {}

    /** A selector door was right-clicked (M19). */
    public static void doorSelected(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BELL, 0.3f, 1.0f);
    }

    /** The lever was pulled with no door selected (M19). */
    public static void noSelection(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.4f, 0.6f);
    }

    /** A run started from the lever or the lodestone menu. */
    public static void runStarts(ServerPlayer player) {
        play(player, Holder.direct(SoundEvents.RESPAWN_ANCHOR_CHARGE), 0.5f, 1.0f);
    }

    /** A run completed on the terminal pad. Rising two-note jingle. */
    public static void runComplete(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BELL, 0.4f, 1.0f);
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.4f, 1.2f);
    }

    /** The run's clock ran out (M10, PD-7 keeps the dungeon open). */
    public static void runTimedOut(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_DIDGERIDOO, 0.5f, 0.8f);
    }

    /** The keystone banked a door offer and levelled up. */
    public static void keystoneLevelUp(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.4f, 0.8f);
    }

    /** The keystone was depleted. Descending two notes. */
    public static void keystoneDepleted(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.4f, 0.8f);
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.4f, 0.6f);
    }

    /** The last trial spawner in a cell was cleared (M10). */
    public static void spawnerCleared(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_HAT, 0.12f, 1.5f);
    }

    /** The lodestone navigation menu opened (M21). */
    public static void menuOpens(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_HAT, 0.2f, 1.0f);
    }

    /** The room was listed in the lobby directory (M20). */
    public static void roomListed(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.3f, 1.0f);
    }

    /** The room was unlisted from the lobby directory (M20). */
    public static void roomUnlisted(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_HAT, 0.2f, 1.0f);
    }

    /** A visitor entered the owner's room (M3/M20). Heard by the owner. */
    public static void visitorArrives(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BELL, 0.3f, 1.0f);
        play(player, SoundEvents.NOTE_BLOCK_BELL, 0.3f, 1.2f);
    }

    /** The room was re-stamped behind the terminal cell (M2 closed loop). */
    public static void roomRelocated(ServerPlayer player) {
        play(player, Holder.direct(SoundEvents.STONE_PLACE), 0.4f, 1.0f);
    }

    /** The lobby directory opened (M20). */
    public static void lobbyOpens(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_HAT, 0.2f, 1.0f);
    }

    /** A visit teleported into a room (M20). */
    public static void visitStarts(ServerPlayer player) {
        play(player, Holder.direct(SoundEvents.ENDERMAN_TELEPORT), 0.3f, 1.0f);
    }

    /** A visit ended and the visitor was returned (M20). */
    public static void visitEnds(ServerPlayer player) {
        play(player, Holder.direct(SoundEvents.ENDERMAN_TELEPORT), 0.3f, 0.7f);
    }

    private static void play(ServerPlayer player, Holder<SoundEvent> sound,
                             float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }
}
