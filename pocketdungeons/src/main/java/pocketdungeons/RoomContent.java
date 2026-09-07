package pocketdungeons;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

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
 * and vaults are the only <em>encounter and loot</em> content, and that has not
 * changed.
 *
 * <p><strong>M5 brings {@link #spawnMobs} back</strong>, restored from the parent
 * of that deletion ({@code 8dbb006^}) but rebuilt as a <em>general-purpose direct
 * spawn path</em> rather than the roster-driven encounter builder it was. T17's
 * reason for the deletion -- two spawners in one room is two difficulty curves --
 * was about <em>encounter</em> cells, where {@link TrialContent} owns the combat
 * loop and a parallel classic spawner would double it. That argument does not
 * reach content which is not the room's fight: a Feral wolf is a feature standing
 * in a corridor, not an encounter. So the boundary is kept where it actually
 * matters -- <strong>{@code spawnMobs} is never called for an {@code encounter}
 * cell</strong> -- and the path itself is open to whatever comes next.
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

    /** How far a repeated spawn may drift off its anchor. One block, as in U3. */
    private static final int SPAWN_JITTER = 1;

    private RoomContent() {}

    /**
     * @return the trial spawner anchors placed for an {@code encounter} cell
     *         (one or more for authored spawners, one for legacy placement),
     *         or an empty list for every other role. M10: the caller collects
     *         these into the run's layout for the spawner-clear completion gate.
     *         ROOM_AUTHORING_SPEC Phase A: returns a list to support multiple
     *         authored trial spawners per cell.
     */
    static List<BlockPos> apply(ServerLevel level, BlockPos cellOrigin, String role, int depth,
                      DifficultyProfile profile, List<BlockPos> spawns, long seed,
                      Set<Affix> affixes, String lootSuffix, String theme, boolean voidedFloor,
                      boolean anomalyCell, String content) {
        List<BlockPos> spawnerAnchors = new ArrayList<>();
        if (anomalyCell) {
            if ("store".equals(content)) {
                // M35 store anomaly: build a village shop interior and spawn a
                // shopkeeper villager. No chest, no spawner, no combat: the
                // store is a safe room the player finds along the critical path.
                StoreShop.build(level, cellOrigin, seed);
                StoreNPC.spawn(level, cellOrigin, seed);
                return List.of();
            }
            // M35: a loose chest off the anomaly table regardless of role -- never
            // a vault, never a keystone or a completion pad. An encounter-role
            // anomaly cell still gets its trial spawner (the fight is real; the
            // room around it is what is wrong), themeless like the chest.
            applyAnomalyChest(level, cellOrigin, seed);
            if ("encounter".equals(role)) {
                BlockPos anchor = TrialContent.applyEncounter(level, cellOrigin, spawns,
                        profile.lootTier(), affixes, null);
                if (anchor != null) {
                    spawnerAnchors.add(anchor);
                }
            }
        } else if (Situations.isRegistered(content)) {
            // M45: the one branch this class carries for the situation
            // catalogue. A registered handler owns its cell outright, so the
            // role switch below never sees it. The handler returns the trial
            // spawner anchor it placed (if any), which travels back to
            // LayoutStamper for the layout's trialSpawners set. Without this,
            // spawners placed by situation handlers would be missing from the
            // layout's spawner set, breaking the spawner-clear completion gate.
            BlockPos anchor = Situations.apply(level, cellOrigin, role, depth, profile, spawns, seed,
                    affixes, lootSuffix, theme, voidedFloor, content);
            if (anchor != null) {
                return List.of(anchor);
            }
            return List.of();
        } else if (role != null) {
            switch (role) {
                case "encounter" -> {
                    removeChests(level, cellOrigin);
                    BlockPos anchor = TrialContent.applyEncounter(level, cellOrigin, spawns,
                            profile.lootTier(), affixes, theme);
                    if (anchor != null) {
                        spawnerAnchors.add(anchor);
                    }
                }
                case "loot" -> TrialContent.applyLoot(level, cellOrigin, profile.lootTier(),
                        affixes.contains(Affix.OMINOUS), seed, lootSuffix);
                case "corridor" -> removeChests(level, cellOrigin);
                default -> { /* entrance and exit carry no chest and no spawn points */ }
            }
        }
        // Molten (M4 T4.5): lava underfoot, and the only source of lava in the
        // game -- a sealed dungeon has none otherwise, and it gates furnace fuel
        // and, with water, obsidian (docs/VISION.md 3.7). Entrance and exit are
        // deliberately excluded: they carry the lobby door and the lodestone
        // pad, and a hazard placed there would make either impassable rather
        // than merely dangerous.
        if (affixes.contains(Affix.MOLTEN) && ("encounter".equals(role) || "loot".equals(role)
                || "corridor".equals(role))) {
            placeMoltenHazards(level, cellOrigin, spawns, seed);
        }
        // Explosive: TNT underfoot with pressure pads on top. Same placement
        // rules as Molten: encounter, loot, corridor only. Entrance and exit
        // excluded to keep lodestone pad and lobby door passable.
        if (affixes.contains(Affix.EXPLOSIVE) && ("encounter".equals(role) || "loot".equals(role)
                || "corridor".equals(role))) {
            placeExplosiveHazards(level, cellOrigin, spawns, seed);
        }
        // Voided: floor ripped open, bedrock gone below. Same cell eligibility
        // as Molten/Explosive. The actual cell selection happens in
        // LayoutStamper before the loop; here we just carve the floor.
        if (voidedFloor) {
            placeVoidedFloor(level, cellOrigin, spawns, seed);
        }
        // Feral (M5 T5.1/T5.2): wolves as a feature, not a fight. Encounter cells
        // are deliberately excluded -- TrialContent owns those, and a wolf pack
        // beside a live trial spawner is exactly the two-difficulty-curves problem
        // T17 deleted the old spawn path over. Entrance and exit are excluded for
        // the same reason Molten excludes them: one is the player's own room, the
        // other the lodestone pad and the reward chests.
        if (affixes.contains(Affix.FERAL) && ("loot".equals(role) || "corridor".equals(role))) {
            FeralContent.apply(level, cellOrigin, spawns, profile.lootTier(), profile.keystoneLevel(), seed);
        }
        return spawnerAnchors;
    }

    /**
     * Spawns {@code count} entities of one type across a cell's authored spawn
     * anchors, seeded off the run so a given seed always stamps the same bodies in
     * the same places.
     *
     * <p>The general-purpose direct-spawn path, restored in M5 (see the class
     * note). It takes the type and the count rather than rolling them from a
     * weighted roster the way the T17-era version did: the roster machinery went
     * with {@code DifficultyProfile}'s U3 fields, and a caller that wants a mix can
     * call this once per type. <strong>Do not call it for an {@code encounter}
     * cell.</strong>
     *
     * <p>{@code after} runs on each spawned entity before the next one is placed --
     * this is where a caller pins, re-skins or otherwise configures what it asked
     * for. Every mob is marked persistent first, without which it despawns out from
     * under a player who backtracks through a room they already walked.
     */
    static void spawnMobs(ServerLevel level, BlockPos cellOrigin, EntityType<?> type, int count,
                          List<BlockPos> spawns, long seed, Consumer<Entity> after) {
        if (count <= 0 || spawns.isEmpty()) {
            return;
        }
        RandomSource random = RandomSource.create(seed ^ cellOrigin.asLong());
        List<BlockPos> shuffled = new ArrayList<>(spawns);
        shuffle(shuffled, random);

        for (int i = 0; i < count; i++) {
            BlockPos base = shuffled.get(i % shuffled.size());
            BlockPos pos = i < shuffled.size() ? base : jitterOrFallBack(level, base, random);
            Entity entity = type.spawn(level, pos, EntitySpawnReason.TRIGGERED);
            if (entity == null) {
                continue;
            }
            if (entity instanceof Mob mob) {
                mob.setPersistenceRequired();
            }
            if (after != null) {
                after.accept(entity);
            }
        }
    }

    /**
     * Once the authored spawn anchors run out, cycles back through them with a
     * small jitter so extra bodies do not stack on exactly the same block. A room's
     * few anchors are frequently close to a wall, so a jittered offset can land in
     * one -- falling back to the exact, always-air anchor rather than skipping the
     * spawn entirely is what keeps {@code count} a guarantee instead of a ceiling
     * that quietly undercounts on small templates.
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

    private static void shuffle(List<BlockPos> list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            BlockPos tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
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
     * Scatters {@link PocketDungeonsConfig#explosiveHazardsPerCell} TNT blocks
     * across the cell's floor with stone pressure plates on top, seeded off
     * the run so a given seed always stamps the same hazards.
     *
     * <p>Same interior margin (3..12) and spawn-anchor skip as
     * {@link #placeMoltenHazards}. TNT sits at floor level (Y=0) with a
     * stone pressure plate on top (Y=1). Stepping on the plate triggers
     * the TNT. The TNT blocks are the reward: the only TNT source in the
     * game, same way Molten is the only lava source.
     */
    private static void placeExplosiveHazards(ServerLevel level, BlockPos cellOrigin,
                                             List<BlockPos> spawns, long seed) {
        int count = PocketDungeonsConfig.explosiveHazardsPerCell();
        if (count <= 0) {
            return;
        }
        Random random = new Random(seed ^ cellOrigin.asLong() ^ 0x4578L);
        int placed = 0;
        int attempts = 0;
        while (placed < count && attempts < count * 8) {
            attempts++;
            int x = 3 + random.nextInt(10);
            int z = 3 + random.nextInt(10);
            BlockPos floorPos = cellOrigin.offset(x, 0, z);
            if (spawns.contains(floorPos)) {
                continue;
            }
            level.setBlock(floorPos, Blocks.TNT.defaultBlockState(), FLAGS);
            level.setBlock(floorPos.above(), Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), FLAGS);
            placed++;
        }
    }

    /**
     * Floor patterns for the Voided affix. Three variants, seeded per cell:
     * <ul>
     *   <li>BROKEN_CROSS (60%): "+" shaped bridge, void in corners, center
     *       missing. Players walk the arms and jump the gap.</li>
     *   <li>HOLES (35%): mostly intact floor with 2-3 random 3x3 gaps.</li>
     *   <li>NO_FLOOR (5%): all interior floor removed, one-block perimeter
     *       ring kept so players can shimmy along the walls.</li>
     * </ul>
     * Spawn anchors skipped on all patterns. Wall ring (x=0, x=15, z=0,
     * z=15) never touched: walls stand on their own floor blocks.
     */
    private static void placeVoidedFloor(ServerLevel level, BlockPos cellOrigin,
                                         List<BlockPos> spawns, long seed) {
        Random random = new Random(seed ^ cellOrigin.asLong() ^ 0xB01DL);
        double roll = random.nextDouble();
        if (roll < 0.60) {
            carveBrokenCross(level, cellOrigin, spawns, random);
        } else if (roll < 0.95) {
            carveHoles(level, cellOrigin, spawns, random);
        } else {
            carveNoFloor(level, cellOrigin, spawns);
        }
    }

    private static void carveBrokenCross(ServerLevel level, BlockPos cellOrigin,
                                         List<BlockPos> spawns, Random random) {
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                boolean onVerticalArm = (x == 7 || x == 8) && (z <= 6 || z >= 9);
                boolean onHorizontalArm = (z == 7 || z == 8) && (x <= 6 || x >= 9);
                boolean onBridge = onVerticalArm || onHorizontalArm;
                if (!onBridge) {
                    BlockPos pos = cellOrigin.offset(x, 0, z);
                    if (!spawns.contains(pos)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
        }
    }

    private static void carveHoles(ServerLevel level, BlockPos cellOrigin,
                                   List<BlockPos> spawns, Random random) {
        int holeCount = 2 + random.nextInt(2);
        for (int h = 0; h < holeCount; h++) {
            int hx = 2 + random.nextInt(10);
            int hz = 2 + random.nextInt(10);
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    int x = hx + dx;
                    int z = hz + dz;
                    if (x < 1 || x >= RoomGeometry.CELL - 1 || z < 1 || z >= RoomGeometry.CELL - 1) {
                        continue;
                    }
                    BlockPos pos = cellOrigin.offset(x, 0, z);
                    if (!spawns.contains(pos)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
        }
    }

    private static void carveNoFloor(ServerLevel level, BlockPos cellOrigin,
                                     List<BlockPos> spawns) {
        for (int x = 2; x < RoomGeometry.CELL - 2; x++) {
            for (int z = 2; z < RoomGeometry.CELL - 2; z++) {
                BlockPos pos = cellOrigin.offset(x, 0, z);
                if (!spawns.contains(pos)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                }
            }
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
            // Authored vaults are not chests: exclude them so they are not
            // double-promoted or retargeted as supply chests.
            if (entry.getValue() instanceof VaultBlockEntity) {
                continue;
            }
            if (entry.getValue() instanceof RandomizableContainer) {
                found.add(pos.immutable());
            }
        }
        return found;
    }

    /**
     * Retargets every chest in the cell to {@link LootTables#ANOMALY}, loose --
     * never a vault. An anomaly room's chest never draws from a themed or
     * tiered table, so it never hands out gear that would tie it back to the
     * run's theme.
     */
    private static void applyAnomalyChest(ServerLevel level, BlockPos cellOrigin, long seed) {
        ResourceKey<LootTable> table = ResourceKey.create(Registries.LOOT_TABLE,
                Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, LootTables.ANOMALY));
        for (BlockPos pos : containers(level, cellOrigin)) {
            if (level.getBlockEntity(pos) instanceof RandomizableContainer c) {
                c.setLootTable(table);
                c.setLootTableSeed(seed ^ pos.asLong());
            }
        }
    }

    private static void removeChests(ServerLevel level, BlockPos cellOrigin) {
        for (BlockPos pos : containers(level, cellOrigin)) {
            // containers() already excludes vaults, so authored vaults survive
            // the encounter-role chest removal. SUPPRESS_DROPS matters here for
            // the same reason RoomBuilder documents it: breaking a container
            // normally scatters its contents as item entities, which then
            // outlive the room they came from.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    private static boolean inCell(BlockPos pos, BlockPos cellOrigin) {
        // M61: a cell may own lower stories below its floor, so the y range
        // extends downward by the max allowed story offset.
        int maxOffset = RoomGeometry.storyOffset(RoomGeometry.MAX_SPAN_Y);
        int dx = pos.getX() - cellOrigin.getX();
        int dy = pos.getY() - cellOrigin.getY();
        int dz = pos.getZ() - cellOrigin.getZ();
        return dx >= 0 && dx < RoomGeometry.CELL
                && dz >= 0 && dz < RoomGeometry.CELL
                && dy >= -maxOffset && dy <= RoomGeometry.CEILING_Y;
    }
}
