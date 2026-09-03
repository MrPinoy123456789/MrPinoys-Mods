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
 * * <p><strong>Positional, not global.</strong> There is no
 * {@code PlayerBlockBreakEvents} registration anywhere else in this mod. So
 * every check here starts with "is this position inside anyone's room right
 * now" ({@link Instances#roomOwnerAt}), which is one bounds check per live
 * instance and costs nothing when the answer is no. M31 9.2 adds a second
 * check on top: the dungeon cells (the quarry, T2.5) outside any room are
 * shell-protected too, but only while the run is still active
 * ({@link Instances#dungeonCellOriginAt}) -- the interior stays breakable and
 * placeable, same as a player room's interior, and the whole thing opens up
 * the moment the first member completes it.
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
        // M43.4: one roomRecordAt scan instead of the three separate
        // roomOwnerAt/roomOriginAt/roomDungeonDoorAt calls (each its own
        // full scan of every live instance) this used to make for the same
        // position.
        InstanceRecord roomRecord = Instances.roomRecordAt(pos);
        UUID roomOwner = roomRecord == null ? null : roomRecord.owner;
        if (roomOwner == null) {
            // M31 9.2: not in anyone's room, but still might be in an active
            // run's dungeon cells. Like the player room, only the shell
            // (floor, walls, ceiling) is immutable; the interior stays
            // breakable so a player can dig, loot and fight their way through.
            // Protection lifts entirely the moment the first member completes.
            BlockPos dungeonCellOrigin = Instances.dungeonCellOriginAt(pos);
            if (dungeonCellOrigin == null) {
                return true;
            }
            return !isShell(pos, dungeonCellOrigin);
        }
        // M18 9.1: the shell is immutable to everyone, the owner included.
        // Checked ahead of the permission mask so a whitelisted guest cannot
        // break the shell either: the shell is not theirs to modify, full
        // stop. Paintings, item frames, carpets, signs, buttons and torches
        // never reach this branch for the shell: they are entities or sit on
        // the face of a wall, not in the wall.
        BlockPos roomOrigin = roomRecord.roomCellOrigin;
        if (roomOrigin != null) {
            // M19 19.7: mod-placed furniture (bulbs, lever, screen blocks,
            // engine block) is equally unbreakable. The shell check stays a
            // pure coordinate test; the furniture check needs to know which
            // wall the selector doors stand on, so it is direction-aware.
            if (isShell(pos, roomOrigin)
                    || isFurniture(pos, roomOrigin, roomRecord.roomDungeonDoor)) {
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
     * {@link #isShell}, covering different positions: the commit lever and its
     * sign in the row in front of the selector wall, the three copper bulbs
     * and the black concrete screen blocks set into that wall, and on the
     * adjacent wall to the left ({@link RoomGeometry#leftOf}) the
     * respawn-anchor engine block with its own screen blocks above it. Every
     * one of them stands whether or not a door has been chosen, except the
     * bulbs, which {@code clearBulbs} hands back to plain wall the moment one
     * is: protecting that course either way is correct, since the block it
     * then holds is the doorway lintel, already shell. None of it can be
     * broken or
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
     * The selector-wall half of {@link #isFurniture}: the lever at Y=2 on the
     * row in front of the wall beside the third door with its sign at Y=3
     * above it, and set into the wall itself a bulb at Y=3 over each of the
     * three doors plus the 8x2 black concrete screen above that.
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
        if (perp == doorPlane && along == 10 && (y == 2 || y == 3)) {
            return true; // the commit lever and the sign above it
        }
        if (perp == wallPlane) {
            if (y == 3 && along >= 7 && along <= 9) {
                return true; // the three copper bulbs, one over each door
            }
            if (y >= 4 && y <= 5 && along >= 4 && along <= 11) {
                return true; // the door screen blocks
            }
        }
        return false;
    }

    /**
     * The engine-wall half of {@link #isFurniture}: on the wall to the left of
     * the selector wall, the respawn anchor at Y=2 and the engine bay above it,
     * which is the screen row at Y=4 with a crying obsidian end block at each
     * side and a course of polished blackstone bezel at Y=3 and Y=5.
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
        if (y == 4 && along >= 4 && along <= 10) {
            return true; // the screen row and its two crying obsidian end blocks
        }
        return (y == 3 || y == 5) && along >= 5 && along <= 9; // the bezel courses
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
