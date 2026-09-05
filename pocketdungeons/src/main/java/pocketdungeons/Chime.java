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

    /**
     * One note per selector door, two semitones apart, so which of the three
     * is lit is audible without reading the screen. Vanilla note-block pitch
     * is {@code 2^((n - 12) / 12)} for semitone {@code n}, which puts the
     * three doors on 0, 2 and 4.
     */
    private static final float[] DOOR_PITCHES = {1.0f, 1.122f, 1.26f};

    /** A selector door was right-clicked (M19). {@code step} is 1, 2 or 3. */
    public static void doorSelected(ServerPlayer player, int step) {
        play(player, SoundEvents.NOTE_BLOCK_BELL, 0.3f,
                DOOR_PITCHES[Math.clamp(step, 1, DOOR_PITCHES.length) - 1]);
    }

    /**
     * A door was previewed that this player cannot commit to yet: the level
     * gate or the fuel cost on a premium door. Lands under
     * {@link #doorSelected}'s note rather than replacing it, so a door that
     * selects but will not open still says so on the click, before the lever.
     */
    public static void doorLocked(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.3f, 0.8f);
    }

    /** The lever was pulled with no door selected (M19). */
    public static void noSelection(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.4f, 0.6f);
    }

    /**
     * The general refusal: a click the mod turned down for a reason the player
     * can act on. Lower than {@link #noSelection} and used everywhere a screen
     * or a chat line says no, so a rejection is never silent.
     */
    public static void refused(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.4f, 0.5f);
    }

    /**
     * (M48) The player started mining a block inside a dungeon cell with the
     * wrong tool. A short, low note so the denial is heard but not punishing,
     * paired with the action-bar message from {@code RoomProtection}.
     */
    public static void wrongTool(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS, 0.2f, 0.7f);
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

    /** A fuel item went into the engine terminal and onto the player's balance. */
    public static void engineFed(ServerPlayer player) {
        play(player, Holder.direct(SoundEvents.RESPAWN_ANCHOR_CHARGE), 0.4f, 1.2f);
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

    /** M25: a Pocket2 door was opened and the player stepped into the pocket. */
    public static void pocketOpens(ServerPlayer player) {
        play(player, Holder.direct(SoundEvents.ENDERMAN_TELEPORT), 0.4f, 1.2f);
    }

    /** M25: the pocket closed and its members were returned to the parent run. */
    public static void pocketCloses(ServerPlayer player) {
        play(player, Holder.direct(SoundEvents.ENDERMAN_TELEPORT), 0.4f, 0.8f);
    }

    private static void play(ServerPlayer player, Holder<SoundEvent> sound,
                             float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }
}
