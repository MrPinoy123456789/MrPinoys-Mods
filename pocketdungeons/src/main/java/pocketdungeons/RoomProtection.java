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
 * or the ender chest, but nothing else.
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
        return isPermitted(level, player, roomOwner);
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
