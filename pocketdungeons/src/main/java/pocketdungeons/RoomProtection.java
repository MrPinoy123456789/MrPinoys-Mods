package pocketdungeons;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * M2 T2.2: the room's permission mask -- owner and whitelist may break, place
 * and open containers inside their room; anyone else may still use a station
 * or the ender chest, but nothing else. M18 9.1 adds one exception on top of
 * the mask: the shell (floor, walls, ceiling, lamps; see {@link #isShell}) is
 * immutable to everyone, the owner included.
 *
 * <p><strong>Positional, not global.</strong> There is no
 * {@code PlayerBlockBreakEvents} registration anywhere else in this mod --
 * the dungeon (the quarry, T2.5) is fully breakable by design, and that has
 * to stay true. So every check here starts with "is this position inside
 * anyone's room right now" ({@link Instances#roomOwnerAt}), which is one
 * bounds check per live instance and costs nothing when the answer is no --
 * the common case, since a room is exactly one cell out of a whole dungeon.
 *
 * <p>Container protection is a check inside {@link RitualListener#onUseBlock},
 * which already owns this mod's one {@code UseBlockCallback} registration and
 * already branches on instance state, rather than a second listener racing
 * the first one for the same event.
 */
final class RoomProtection {

    private RoomProtection() {}

    static void register() {
        PlayerBlockBreakEvents.BEFORE.register(RoomProtection::beforeBlockBreak);
    }

    private static boolean beforeBlockBreak(Level level, Player player, BlockPos pos,
                                             BlockState state, BlockEntity blockEntity) {
        UUID roomOwner = Instances.roomOwnerAt(pos);
        if (roomOwner == null) {
            return true; // outside every room -- the common case, one bounds check
        }
        // M18 9.1: the shell is immutable to everyone, the owner included.
        // Checked ahead of the permission mask so a whitelisted guest cannot
        // break the shell either -- the shell is not theirs to modify, full
        // stop. Paintings, item frames, carpets, signs, buttons and torches
        // never reach this branch for the shell: they are entities or sit on
        // the face of a wall, not in the wall.
        BlockPos roomOrigin = Instances.roomOriginAt(pos);
        if (roomOrigin != null && isShell(pos, roomOrigin)) {
            return false;
        }
        return isPermitted(level, player, roomOwner);
    }

    /**
     * Whether {@code pos} is part of the room's immutable shell (M18 9.1): the
     * floor (Y=0), the wall ring (x=0, x=15, z=0, z=15 at Y=1..5), the ceiling
     * (Y=6, which covers the slabs, the stair fixtures and the lamps), and the
     * four ceiling lamp positions (already inside the ceiling row). The interior
     * (x=1..14, z=1..14, Y=1..5) is the owner's build space and is never shell.
     *
     * <p>A pure coordinate test against the room origin, with no block-state
     * lookup: the check protects whatever block sits in the shell position, so
     * it keeps working when a future skin swap (M24) changes the block types
     * there. The wall lodestone and the post-selection double doors sit in the
     * wall ring, so they are shell too, and cannot be broken or replaced.
     */
    static boolean isShell(BlockPos pos, BlockPos roomOrigin) {
        int x = pos.getX() - roomOrigin.getX();
        int y = pos.getY() - roomOrigin.getY();
        int z = pos.getZ() - roomOrigin.getZ();
        if (x < 0 || x >= RoomGeometry.CELL || z < 0 || z >= RoomGeometry.CELL
                || y < 0 || y > RoomGeometry.CEILING_Y) {
            return false; // outside the room's own 16x16x7 box
        }
        if (y == 0 || y == RoomGeometry.CEILING_Y) {
            return true; // floor row and ceiling row, lamps included
        }
        return x == 0 || x == RoomGeometry.CELL - 1
                || z == 0 || z == RoomGeometry.CELL - 1; // the wall ring
    }

    /**
     * Whether {@code actor} may act as an owner or whitelisted member of the
     * room owned by {@code roomOwner} would. Not container- or station-aware --
     * callers that need the "stations and the ender chest stay open to
     * everyone" carve-out check that first and only fall through to this for
     * the block-break/place/generic-container case.
     */
    static boolean isPermitted(Level level, Player player, UUID roomOwner) {
        if (player.getUUID().equals(roomOwner)) {
            return true;
        }
        if (!(player instanceof ServerPlayer) || level.isClientSide()) {
            return false;
        }
        var server = level.getServer();
        if (server == null) {
            return false;
        }
        return RoomWhitelist.forServer(server).isPermitted(roomOwner, player.getUUID());
    }

    /**
     * Whether a use-block interaction on {@code state} at {@code pos} should be
     * denied for {@code player}: inside someone's room, that player is neither
     * the owner nor whitelisted, and the block is a lootable container -- a
     * chest, barrel, shulker box and the like, not a station or the ender
     * chest, which stay open to everyone (T2.2's table).
     */
    static boolean denyContainerUse(Level level, Player player, BlockPos pos, BlockEntity blockEntity) {
        if (!(blockEntity instanceof RandomizableContainer)) {
            return false; // not a lootable container -- a station, unaffected
        }
        UUID roomOwner = Instances.roomOwnerAt(pos);
        if (roomOwner == null) {
            return false;
        }
        return !isPermitted(level, player, roomOwner);
    }
}
