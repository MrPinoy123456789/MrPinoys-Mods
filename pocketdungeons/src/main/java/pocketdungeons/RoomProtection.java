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
        // break the shell either: the shell is not theirs to modify, full
        // stop. Paintings, item frames, carpets, signs, buttons and torches
        // never reach this branch for the shell: they are entities or sit on
        // the face of a wall, not in the wall.
        BlockPos roomOrigin = Instances.roomOriginAt(pos);
        if (roomOrigin != null) {
            // M19 19.7: mod-placed furniture (bulbs, lever, screen blocks,
            // engine block) is equally unbreakable. The shell check stays a
            // pure coordinate test; the furniture check needs to know which
            // wall the selector doors stand on, so it is direction-aware.
            if (isShell(pos, roomOrigin)
                    || isFurniture(pos, roomOrigin, Instances.roomDungeonDoorAt(pos))) {
                return false;
            }
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
     * M19 19.7: whether {@code pos} is mod-placed room furniture, relative to
     * {@code roomOrigin} and the wall the selector doors stand on
     * ({@code selectorWall}). A pure coordinate test, the same shape as
     * {@link #isShell}, covering different positions: the four copper bulbs
     * and the commit lever in the row in front of the selector wall, the black
     * concrete screen blocks set into that wall, and on the adjacent wall to
     * the left ({@link RoomGeometry#leftOf}) the respawn-anchor engine block
     * with its own screen blocks above it. None of it can be broken or
     * replaced by a player; {@code RoomTemplateGenerator.placeFurniture} is
     * the only writer and it bypasses {@code RoomProtection} entirely.
     *
     * <p>The positions here must stay in lockstep with
     * {@code RoomTemplateGenerator.placeFurniture} and
     * {@code RoomTemplateGenerator.clearFurniture}: the three read the same
     * wall-relative coordinates, and {@code RoomFurnitureTest} pins them.
     */
    static boolean isFurniture(BlockPos pos, BlockPos roomOrigin, DoorMask.Direction selectorWall) {
        if (selectorWall == null) {
            return false;
        }
        int x = pos.getX() - roomOrigin.getX();
        int y = pos.getY() - roomOrigin.getY();
        int z = pos.getZ() - roomOrigin.getZ();
        if (x < 0 || x >= RoomGeometry.CELL || z < 0 || z >= RoomGeometry.CELL
                || y < 0 || y > RoomGeometry.CEILING_Y) {
            return false; // outside the room's own 16x16x7 box
        }
        return selectorWallFurniture(x, y, z, selectorWall)
                || engineWallFurniture(x, y, z, selectorWall);
    }

    /**
     * The selector-wall half of {@link #isFurniture}: bulbs above each door
     * and above the lever at Y=4 on the row in front of the wall, the lever
     * itself at Y=2 beside the third door, and the 8x2 black concrete screen
     * set into the wall above them.
     */
    private static boolean selectorWallFurniture(int x, int y, int z, DoorMask.Direction wall) {
        int along;
        int perp;
        int doorPlane;
        int wallPlane;
        switch (wall) {
            case NORTH -> {
                along = x;
                perp = z;
                doorPlane = 1;
                wallPlane = 0;
            }
            case SOUTH -> {
                along = x;
                perp = z;
                doorPlane = RoomGeometry.CELL - 2;
                wallPlane = RoomGeometry.CELL - 1;
            }
            case EAST -> {
                along = z;
                perp = x;
                doorPlane = RoomGeometry.CELL - 2;
                wallPlane = RoomGeometry.CELL - 1;
            }
            case WEST -> {
                along = z;
                perp = x;
                doorPlane = 1;
                wallPlane = 0;
            }
            default -> {
                return false;
            }
        }
        if (perp == doorPlane) {
            if (y == 4 && along >= 7 && along <= 10) {
                return true; // the four copper bulbs
            }
            if (y == 2 && along == 10) {
                return true; // the commit lever
            }
        }
        if (perp == wallPlane && y >= 4 && y <= 5 && along >= 4 && along <= 11) {
            return true; // the door screen blocks
        }
        return false;
    }

    /**
     * The engine-wall half of {@link #isFurniture}: the respawn anchor at
     * Y=2 on the wall to the left of the selector wall, and its 2x2 black
     * concrete screen above it.
     */
    private static boolean engineWallFurniture(int x, int y, int z, DoorMask.Direction selectorWall) {
        DoorMask.Direction engineWall = RoomGeometry.leftOf(selectorWall);
        int along;
        switch (engineWall) {
            case NORTH -> {
                if (z != 0) {
                    return false;
                }
                along = x;
            }
            case SOUTH -> {
                if (z != RoomGeometry.CELL - 1) {
                    return false;
                }
                along = x;
            }
            case EAST -> {
                if (x != RoomGeometry.CELL - 1) {
                    return false;
                }
                along = z;
            }
            case WEST -> {
                if (x != 0) {
                    return false;
                }
                along = z;
            }
            default -> {
                return false;
            }
        }
        if (y == 2 && along == 7) {
            return true; // the engine block
        }
        return y >= 4 && y <= 5 && along >= 7 && along <= 8; // the engine screen blocks
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
