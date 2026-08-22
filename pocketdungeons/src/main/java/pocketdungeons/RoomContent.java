package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;

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
 *   <tr><td>{@code encounter}</td><td>remove the chest, spawn mobs at the spawn points</td></tr>
 *   <tr><td>{@code loot}</td><td>keep and retarget the chest, spawn nothing</td></tr>
 *   <tr><td>{@code corridor}</td><td>remove the chest, spawn nothing</td></tr>
 *   <tr><td>{@code entrance}, {@code exit}</td><td>nothing; their templates carry neither</td></tr>
 * </table>
 *
 * <h2>U6: role dispatch stays, the content moves</h2>
 *
 * <p>With {@code trialsEnabled} the {@code encounter} and {@code loot} branches
 * hand off to {@link TrialContent} -- a trial spawner instead of a handful of
 * mobs spawned all at once, a vault instead of a free chest. Role dispatch and
 * the chest-removal machinery stay here, because they are what decides <em>which</em>
 * of those a cell gets, and that question is unchanged.
 *
 * <p>The U3 path below is not dead code: it is the {@code trialsEnabled: false}
 * rollback, and it is the only one. Keeping it costs about sixty lines and buys
 * the ability to run the two side by side while tuning.
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

    /** Jitter radius, in blocks, applied when an encounter needs more mobs than it has spawn jigsaws. */
    private static final int SPAWN_JITTER = 1;

    private RoomContent() {}

    static void apply(ServerLevel level, BlockPos cellOrigin, String role, int depth,
                      DifficultyProfile profile, List<BlockPos> spawns, long seed,
                      boolean ominousRun) {
        if (role == null) {
            applySpawnerDenSwitch(level, cellOrigin);
            return;
        }
        if (TrialContent.enabled()) {
            applyTrials(level, cellOrigin, role, depth, profile, spawns, seed, ominousRun);
            return;
        }

        applySpawnerDenSwitch(level, cellOrigin);
        switch (role) {
            case "encounter" -> {
                removeChests(level, cellOrigin);
                spawnMobs(level, cellOrigin, depth, profile, spawns, seed);
            }
            case "loot" -> retargetChests(level, cellOrigin, profile, seed);
            case "corridor" -> removeChests(level, cellOrigin);
            default -> { /* entrance and exit carry no chest and no spawn points */ }
        }
    }

    /**
     * The U6 branch. {@code corridor} still loses its placeholder chest -- a
     * corridor that pays is not a corridor -- and {@code entrance}/{@code exit}
     * still carry nothing, so only the two roles that hold content change hands.
     */
    private static void applyTrials(ServerLevel level, BlockPos cellOrigin, String role, int depth,
                                    DifficultyProfile profile, List<BlockPos> spawns, long seed,
                                    boolean ominousRun) {
        boolean ominous = TrialContent.ominousAt(depth, profile.pathLength(), ominousRun);
        switch (role) {
            case "encounter" -> {
                removeChests(level, cellOrigin);
                TrialContent.applyEncounter(level, cellOrigin, spawns, profile.lootTier(),
                        ominous, ominousRun);
            }
            case "loot" -> TrialContent.applyLoot(level, cellOrigin, profile.lootTier(),
                    ominous, ominousRun, seed);
            case "corridor" -> {
                removeChests(level, cellOrigin);
                applySpawnerDenSwitch(level, cellOrigin);
            }
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

    /**
     * Retargets a {@code loot} cell's chest to the run's tier table.
     *
     * <p>The seed is set explicitly rather than left to vanilla's own
     * assignment: PLAN.md's M1 notes record that a placed chest gets a
     * non-zero {@code LootTableSeed} on its own even when nothing calls
     * {@code setLootTableSeed}, and tier 2/3 do have real randomness, so
     * deriving the seed from the run seed is what keeps a seed reproducible
     * for debugging.
     */
    private static void retargetChests(ServerLevel level, BlockPos cellOrigin,
                                       DifficultyProfile profile, long seed) {
        ResourceKey<LootTable> table = ResourceKey.create(Registries.LOOT_TABLE,
                id("chests/tier_" + profile.lootTier()));
        for (BlockPos pos : containers(level, cellOrigin)) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof RandomizableContainer container) {
                container.setLootTable(table);
                container.setLootTableSeed(seed ^ pos.asLong());
            }
        }
    }

    /**
     * Spawns {@code profile.mobCount(depth)} mobs at {@code spawns}, rolled
     * from {@code profile.mobRoster(profile.effectiveTier(depth))}. Order is
     * shuffled and, once the spawn points run out, cycled with a small jitter
     * so extra mobs do not stack on the same block.
     */
    private static void spawnMobs(ServerLevel level, BlockPos cellOrigin, int depth,
                                  DifficultyProfile profile, List<BlockPos> spawns, long seed) {
        if (spawns.isEmpty()) {
            return;
        }
        RandomSource random = RandomSource.create(seed ^ cellOrigin.asLong());
        List<BlockPos> shuffled = new ArrayList<>(spawns);
        shuffle(shuffled, random);

        int count = profile.mobCount(depth);
        List<DifficultyProfile.WeightedEntry> roster = profile.mobRoster(profile.effectiveTier(depth));
        for (int i = 0; i < count; i++) {
            BlockPos base = shuffled.get(i % shuffled.size());
            BlockPos pos = i < shuffled.size() ? base : jitterOrFallBack(level, base, random);
            spawnOne(level, pos, roster, random);
        }
    }

    /**
     * Once the spawn jigsaws run out, cycles back through them with a small
     * jitter so extra mobs do not stack on exactly the same block. A room's
     * few authored spawn points are frequently close to a wall, so a jittered
     * offset can land on a solid block -- falling back to the exact, always-air
     * jigsaw position rather than skipping the mob entirely is what keeps
     * {@code mobCount} an actual guarantee instead of a ceiling that quietly
     * undercounts on small templates.
     */
    private static BlockPos jitterOrFallBack(ServerLevel level, BlockPos base, RandomSource random) {
        for (int attempt = 0; attempt < 4; attempt++) {
            int jx = random.nextInt(2 * SPAWN_JITTER + 1) - SPAWN_JITTER;
            int jz = random.nextInt(2 * SPAWN_JITTER + 1) - SPAWN_JITTER;
            BlockPos candidate = base.offset(jx, 0, jz);
            if (level.getBlockState(candidate).isAir()) {
                return candidate;
            }
        }
        return base;
    }

    private static void spawnOne(ServerLevel level, BlockPos pos,
                                 List<DifficultyProfile.WeightedEntry> roster, RandomSource random) {
        String entityId = roll(roster, random);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(entityId));
        Entity entity = type.spawn(level, pos, EntitySpawnReason.TRIGGERED);
        if (entity == null) {
            return;
        }
        if (entity instanceof Mob mob) {
            // Without this the mob despawns out from under a player who
            // backtracks through an already-cleared room.
            mob.setPersistenceRequired();
        }
    }

    private static String roll(List<DifficultyProfile.WeightedEntry> roster, RandomSource random) {
        int total = 0;
        for (DifficultyProfile.WeightedEntry entry : roster) {
            total += entry.weight();
        }
        int pick = random.nextInt(total);
        for (DifficultyProfile.WeightedEntry entry : roster) {
            pick -= entry.weight();
            if (pick < 0) {
                return entry.entityId();
            }
        }
        return roster.get(roster.size() - 1).entityId();
    }

    private static void shuffle(List<BlockPos> list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            BlockPos tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    /**
     * Spawner dens need no other code: the spawner is authored into the
     * template and runs itself. If {@code spawnerDensEnabled} is false this is
     * the kill switch -- swap it for plain stone rather than re-authoring the
     * template or editing the manifest.
     */
    private static void applySpawnerDenSwitch(ServerLevel level, BlockPos cellOrigin) {
        if (PocketDungeonsConfig.spawnerDensEnabled()) {
            return;
        }
        LevelChunk chunk = level.getChunkAt(cellOrigin);
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            if (!inCell(pos, cellOrigin)) {
                continue;
            }
            if (entry.getValue() instanceof SpawnerBlockEntity) {
                level.setBlock(pos, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), FLAGS);
            }
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, path);
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
