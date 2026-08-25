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
import java.util.Random;
import java.util.Set;

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
 *   <tr><td>{@code encounter}</td><td>remove the chest, hand off to {@link TrialContent} for a trial spawner</td></tr>
 *   <tr><td>{@code loot}</td><td>hand off to {@link TrialContent} for a vault</td></tr>
 *   <tr><td>{@code corridor}</td><td>remove the chest</td></tr>
 *   <tr><td>{@code entrance}, {@code exit}</td><td>nothing; their templates carry neither</td></tr>
 * </table>
 *
 * <p><strong>T17 deleted the U3 rollback path</strong> this class used to carry
 * beside the trial-spawner one: {@code spawnMobs} and its helpers, and the
 * {@code trialsEnabled: false} branch that chose between them. Trial spawners
 * and vaults are the only encounter and loot content now.
 */
final class RoomContent {

    /**
     * {@code UPDATE_SUPPRESS_DROPS} alone only suppresses a removed block's
     * own item drop; a container's *contents* are dropped separately by
     * {@code BlockEntity.preRemoveSideEffects}, gated by
     * {@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS}. Without that second flag,
     * breaking a chest that still has a pending (never-opened) loot table
     * lazily unpacks it right there -- any container access does, see
     * {@code RandomizableContainerBlockEntity.getItem} -- and scatters the
     * result on the ground as the chest disappears. This is what made
     * {@code encounter}/{@code corridor} cells (which remove their
     * placeholder chest) show loose items where the chest used to be.
     */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private RoomContent() {}

    static void apply(ServerLevel level, BlockPos cellOrigin, String role, int depth,
                      DifficultyProfile profile, List<BlockPos> spawns, long seed,
                      Set<Affix> affixes) {
        if (role == null) {
            return;
        }
        switch (role) {
            case "encounter" -> {
                removeChests(level, cellOrigin);
                TrialContent.applyEncounter(level, cellOrigin, spawns, profile.lootTier(), affixes);
            }
            case "loot" -> TrialContent.applyLoot(level, cellOrigin, profile.lootTier(),
                    affixes.contains(Affix.OMINOUS), seed);
            case "corridor" -> removeChests(level, cellOrigin);
            default -> { /* entrance and exit carry no chest and no spawn points */ }
        }
        // Molten (M4 T4.5): lava underfoot, and the only source of lava in the
        // game -- a sealed dungeon has none otherwise, and it gates furnace fuel
        // and, with water, obsidian (VISION.md 3.7). Entrance and exit are
        // deliberately excluded: they carry the lobby door and the lodestone
        // pad, and a hazard placed there would make either impassable rather
        // than merely dangerous.
        if (affixes.contains(Affix.MOLTEN) && ("encounter".equals(role) || "loot".equals(role)
                || "corridor".equals(role))) {
            placeMoltenHazards(level, cellOrigin, spawns, seed);
        }
    }

    /**
     * Scatters {@link PocketDungeonsConfig#moltenHazardsPerCell} lava blocks
     * across the cell's floor, seeded off the run so a given seed always stamps
     * the same hazards.
     *
     * <p>Kept to the interior margin ({@code 3..12} of a 16-wide cell) rather
     * than the full floor: that clears both the wall/door band and the two
     * anchor columns ({@code 7}/{@code 8}) the door and spawn jigsaws use, so a
     * cell never comes out impassable. Spawn anchors themselves are skipped
     * explicitly on top of that, since a spawner sitting in lava is its own kind
     * of broken.
     */
    private static void placeMoltenHazards(ServerLevel level, BlockPos cellOrigin,
                                           List<BlockPos> spawns, long seed) {
        int count = PocketDungeonsConfig.moltenHazardsPerCell();
        if (count <= 0) {
            return;
        }
        Random random = new Random(seed ^ cellOrigin.asLong());
        int placed = 0;
        int attempts = 0;
        while (placed < count && attempts < count * 8) {
            attempts++;
            int x = 3 + random.nextInt(10);
            int z = 3 + random.nextInt(10);
            BlockPos pos = cellOrigin.offset(x, 0, z);
            if (spawns.contains(pos)) {
                continue;
            }
            level.setBlock(pos, Blocks.LAVA.defaultBlockState(), FLAGS);
            placed++;
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
