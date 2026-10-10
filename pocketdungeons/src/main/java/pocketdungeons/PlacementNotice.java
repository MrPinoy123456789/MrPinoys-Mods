package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells a player, as they place a block in the dungeon, whether it will last
 * (playtest 2026-10-02-1). The safe room is saved with the owner's room, so a
 * block placed there is still there next time: green, with a chime. Anywhere
 * else (the staging room, which is rebuilt every interval, a floor, a visit's
 * copy of a room) is gone when that cell closes: red, with the wrong tool note,
 * the same shape as {@link RoomProtection}'s wrong tool warning.
 *
 * <p>The line goes to the action bar, so building does not flood chat. The
 * sound plays at most once a second, so a row of blocks is not a row of notes.
 */
public final class PlacementNotice {

    private static final long SOUND_GAP_TICKS = 20;

    /** Player to the game tick their last placement sound played. */
    private static final Map<UUID, Long> LAST_SOUND = new HashMap<>();

    private PlacementNotice() {}

    /** Forgets when a player last heard the placement sound, for a logout. */
    static void forgetPlayer(UUID player) {
        LAST_SOUND.remove(player);
    }

    /**
     * A block was placed at {@code pos} in the dungeon dimension by {@code player}.
     * Called from {@code BlockItemPlaceMixin} after the placement succeeded.
     * Silent in a build room (the editor has its own save) and for a player
     * with no instance.
     */
    public static void placed(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || record.adminBuild) {
            return;
        }
        boolean kept = keeps(pos);
        Component line = kept
                ? Component.literal(record.owner != null && record.owner.equals(player.getUUID())
                        ? "Saved with your safe room. It will be here when you come back."
                        : "Saved with this safe room. It will be here next time.")
                        .withStyle(ChatFormatting.GREEN)
                : Component.literal("This will not last. Only a safe room keeps what you build.")
                        .withStyle(ChatFormatting.RED);
        player.connection.send(new ClientboundSetActionBarTextPacket(line));
        long now = player.level().getGameTime();
        Long last = LAST_SOUND.get(player.getUUID());
        if (last == null || now - last >= SOUND_GAP_TICKS || now < last) {
            LAST_SOUND.put(player.getUUID(), now);
            if (kept) {
                Chime.placementKept(player);
            } else {
                Chime.wrongTool(player);
            }
        }
    }

    /**
     * Whether a block at {@code pos} is saved: it is inside a live instance's
     * safe room, and that room is the owner's own rather than a visit's copy
     * ({@link RunLifecycle#saveRoom} skips a visit). The staging room shares
     * the record but not the cell, so it never counts.
     */
    static boolean keeps(BlockPos pos) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            BlockPos origin = record.roomCellOrigin;
            if (origin != null && !record.visitInstance && !record.adminBuild
                    && pos.getX() >= origin.getX() && pos.getX() < origin.getX() + RoomGeometry.CELL
                    && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + RoomGeometry.CELL
                    && pos.getY() > origin.getY() && pos.getY() < origin.getY() + RoomGeometry.CEILING_Y) {
                return true;
            }
        }
        return false;
    }
}
