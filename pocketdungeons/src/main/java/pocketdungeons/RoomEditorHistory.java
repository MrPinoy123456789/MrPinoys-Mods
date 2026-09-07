package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks block changes in the build room for undo/redo. Each entry records
 * the position and the block state before and after the change.
 *
 * <p>History is capped at 50 operations per player. An "operation" is a
 * single block place or break, or a contiguous burst within a 1-second
 * window. For simplicity in this initial implementation, each block change
 * is one operation; burst grouping is a future refinement.
 *
 * <p>See {@code docs/reference/ROOM_AUTHORING_SPEC.md} section 4.12.
 */
public final class RoomEditorHistory {

    private static final int MAX_HISTORY = 50;

    private static final Map<UUID, Deque<BlockChange>> UNDO = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<BlockChange>> REDO = new ConcurrentHashMap<>();

    private RoomEditorHistory() {}

    /**
     * Registers the after-break event for undo history recording.
     */
    static void register() {
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER
                .register((level, player, pos, state, blockEntity) -> {
                    if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                        return;
                    }
                    if (!serverPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                        return;
                    }
                    InstanceRecord record = InstanceRegistry.byMember.get(serverPlayer.getUUID());
                    if (record == null || !record.adminBuild) {
                        return;
                    }
                    record(serverPlayer, pos, state, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                });
    }

    /**
     * Records a block change for undo. Called when a block is placed or
     * broken in a build room.
     */
    public static void record(ServerPlayer player, BlockPos pos, BlockState before, BlockState after) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !record.adminBuild) {
            return;
        }
        Deque<BlockChange> undo = UNDO.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        undo.push(new BlockChange(pos.immutable(), before, after));
        if (undo.size() > MAX_HISTORY) {
            ((ArrayDeque<BlockChange>) undo).removeLast();
        }
        // Clear redo on new change.
        REDO.remove(player.getUUID());
    }

    /**
     * Undoes the last block change. Returns true if something was undone.
     */
    static boolean undo(ServerPlayer player) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !record.adminBuild) {
            return false;
        }
        Deque<BlockChange> undo = UNDO.get(player.getUUID());
        if (undo == null || undo.isEmpty()) {
            return false;
        }
        BlockChange change = undo.pop();
        ServerLevel level = player.level().getServer()
                .getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return false;
        }
        level.setBlock(change.pos, change.before, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
        Deque<BlockChange> redo = REDO.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        redo.push(change);
        return true;
    }

    /**
     * Redoes the last undone block change. Returns true if something was redone.
     */
    static boolean redo(ServerPlayer player) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !record.adminBuild) {
            return false;
        }
        Deque<BlockChange> redo = REDO.get(player.getUUID());
        if (redo == null || redo.isEmpty()) {
            return false;
        }
        BlockChange change = redo.pop();
        ServerLevel level = player.level().getServer()
                .getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return false;
        }
        level.setBlock(change.pos, change.after, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
        Deque<BlockChange> undo = UNDO.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        undo.push(change);
        return true;
    }

    /**
     * Clears history for a player (on save, exit, or teardown).
     */
    static void clear(UUID uuid) {
        UNDO.remove(uuid);
        REDO.remove(uuid);
    }

    private record BlockChange(BlockPos pos, BlockState before, BlockState after) {}
}
