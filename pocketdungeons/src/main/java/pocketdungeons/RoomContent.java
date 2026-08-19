package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies a cell's <em>role</em> to the room that was just stamped into it.
 *
 * <p>Room templates do not know what they are for: a coverage-floor room is
 * authored with both a chest and spawn points, and which of them survives is
 * decided here from the role the planner assigned. That is what lets five shape
 * templates satisfy fifteen (mask, role) combinations instead of needing one
 * template each.
 *
 * <table>
 *   <caption>Role dispatch</caption>
 *   <tr><td>{@code encounter}</td><td>spawn mobs at the spawn points, remove the chest</td></tr>
 *   <tr><td>{@code loot}</td><td>keep and retarget the chest, spawn nothing</td></tr>
 *   <tr><td>{@code corridor}</td><td>remove the chest, spawn nothing</td></tr>
 *   <tr><td>{@code entrance}, {@code exit}</td><td>nothing; their templates carry neither</td></tr>
 * </table>
 *
 * <p>Mob spawning is M6a. This milestone ships the chest half, which is what the
 * stamper needs to produce a correct room, and takes the spawn-point list so the
 * call site does not have to change when the other half lands.
 */
final class RoomContent {

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

    private RoomContent() {}

    static void apply(ServerLevel level, BlockPos cellOrigin, String role, List<BlockPos> spawns) {
        if (role == null) {
            return;
        }
        switch (role) {
            case "loot" -> { /* the chest stays; M6a retargets it to the run's tier */ }
            case "encounter", "corridor" -> removeChests(level, cellOrigin);
            default -> { /* entrance and exit carry no chest and no spawn points */ }
        }
    }

    /**
     * Every container block entity inside the cell.
     *
     * <p>A cell is exactly one chunk (slot origins are chunk-aligned, see
     * {@code PlanGeometry}), so this is a direct lookup of that chunk's block
     * entity map rather than a 1,792-position scan. The bounds test still runs
     * because the chunk map is keyed by world position and a future non-aligned
     * layout would otherwise silently pick up a neighbour's chest.
     */
    static List<BlockPos> containers(ServerLevel level, BlockPos cellOrigin) {
        LevelChunk chunk = level.getChunkAt(cellOrigin);
        List<BlockPos> found = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            if (!inCell(pos, cellOrigin)) {
                continue;
            }
            if (entry.getValue() instanceof RandomizableContainer) {
                found.add(pos.immutable());
            }
        }
        return found;
    }

    private static void removeChests(ServerLevel level, BlockPos cellOrigin) {
        for (BlockPos pos : containers(level, cellOrigin)) {
            // SUPPRESS_DROPS matters here for the same reason RoomBuilder documents
            // it: breaking a container normally scatters its contents as item
            // entities, which then outlive the room they came from.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    private static boolean inCell(BlockPos pos, BlockPos cellOrigin) {
        int dx = pos.getX() - cellOrigin.getX();
        int dy = pos.getY() - cellOrigin.getY();
        int dz = pos.getZ() - cellOrigin.getZ();
        return dx >= 0 && dx < RoomGeometry.CELL
                && dz >= 0 && dz < RoomGeometry.CELL
                && dy >= 0 && dy <= RoomGeometry.CEILING_Y;
    }
}
