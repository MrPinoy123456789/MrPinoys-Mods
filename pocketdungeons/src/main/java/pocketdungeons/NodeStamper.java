package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Dungeon structure W4: what a stamped room cell gets after its template and room
 * content are down.
 *
 * <ol>
 *   <li><strong>Light</strong> (D17, D21): a cell whose effective darkness
 *       ({@link DungeonLight#effective}) is {@code dim} or {@code dark} loses its
 *       light sources, never in a room that {@code requiresLight}.</li>
 *   <li><strong>Declared nodes</strong> (D19): each room metadata {@code nodes}
 *       entry places its block where the cell is air and registers the position as
 *       a resource node.</li>
 *   <li><strong>Palette nodes</strong> (D19): any interior block that is in the
 *       floor's dungeon {@code nodePalette} counts as a node, so the ore and log
 *       decoration the templates already hold becomes mineable without a metadata
 *       edit.</li>
 *   <li><strong>Hidden ore</strong> (design 2026-10-06-1 item 7): a dungeon with a
 *       {@code hiddenOre} field buries a few pockets of ore in the room's solid rock,
 *       registered as nodes like any other and never touching air
 *       ({@link HiddenOrePlanner}).</li>
 * </ol>
 *
 * <p>The registered positions are returned to the caller, which carries them on the
 * {@link InstanceLayout} (see {@link FloorState#nodes}); the break rule
 * ({@link BreakRule}) reads them. Nothing here changes a block that is not a light
 * source or an air cell a node was declared in.
 */
final class NodeStamper {

    private NodeStamper() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    /** The id of the Endless Mine dungeon, whose palette every Mine floor uses. */
    static final String ENDLESS_MINE = "pocketdungeons:endless_mine";

    /**
     * What a floor tells every cell it stamps: the dungeon's node palette (full
     * block ids) and the floor node's minimum darkness.
     */
    record Context(Set<String> palette, String nodeLight, DungeonDef.HiddenOre hiddenOre,
                   List<String> hiddenBlocks) {

        /** No dungeon behind the stamp (admin builds, Pocket2): declared nodes only, no darkening. */
        static final Context NONE = new Context(Set.of(), DungeonRoomMeta.LIGHT_LIT);

        /** A context with no hidden ore. */
        Context(Set<String> palette, String nodeLight) {
            this(palette, nodeLight, null, List.of());
        }

        Context {
            palette = Set.copyOf(palette);
            nodeLight = nodeLight == null ? DungeonRoomMeta.LIGHT_LIT : nodeLight;
            hiddenBlocks = List.copyOf(hiddenBlocks);
        }
    }

    /**
     * The context for the floor behind {@code dungeonId} and {@code nodeId}; an
     * Endless Mine floor uses the mine dungeon's palette whatever door it came
     * through. An unknown dungeon gives {@link Context#NONE}.
     */
    static Context contextFor(String dungeonId, String nodeId, boolean mine) {
        return contextFor(dungeonId, nodeId, mine, 0);
    }

    /**
     * As {@link #contextFor(String, String, boolean)} for a floor {@code floorNumber} deep (1 for the first):
     * the hidden ore of a dungeon that deepens ({@link DungeonDef.HiddenOre#atDepth}) is richer the deeper it goes.
     */
    static Context contextFor(String dungeonId, String nodeId, boolean mine, int floorNumber) {
        DungeonDefs dungeons = DungeonDefs.current();
        DungeonDef def = dungeons == null ? null : dungeons.byId(mine ? ENDLESS_MINE : dungeonId);
        if (def == null) {
            return Context.NONE;
        }
        DungeonDef.Node node = mine ? null : def.node(nodeId);
        DungeonDef.HiddenOre hidden = def.hiddenOre() == null ? null : def.hiddenOre().atDepth(floorNumber);
        // A structural block in a palette (a third-party pack that skipped the validator) would
        // make walls mineable, so it is never a node.
        return new Context(def.nodePalette().stream().filter(b -> !DungeonDef.isStructuralBlock(b))
                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                node == null ? DungeonRoomMeta.LIGHT_LIT : node.light(),
                hidden,
                hidden == null ? List.of() : hidden.blocksOr(def.nodePalette()).stream()
                        .filter(b -> !DungeonDef.isStructuralBlock(b)).toList());
    }

    /**
     * Applies light, declared nodes and palette nodes to one stamped cell.
     *
     * @param quarterTurns the rotation the room was stamped at
     * @param seed         for choosing which positions of a counted node box are nodes
     * @param nodesOut     receives every node position
     */
    static void applyCell(ServerLevel level, BlockPos cellOrigin, int quarterTurns,
                          DungeonRoomMeta meta, Context ctx, long seed, Set<BlockPos> nodesOut) {
        stripLight(level, cellOrigin, meta,
                DungeonLight.effective(meta.light, ctx.nodeLight(), meta.requiresLight));
        placeDeclaredNodes(level, cellOrigin, quarterTurns, meta, seed, nodesOut);
        scanPaletteNodes(level, cellOrigin, meta.spanY, ctx.palette(), nodesOut);
        placeHiddenOre(level, cellOrigin, meta.spanY, ctx, seed, nodesOut);
    }

    /**
     * Design 2026-10-06-1 item 7: buries the dungeon's hidden ore pockets in this cell's solid
     * rock, after the declared and palette nodes, and registers every block as a node exactly
     * as a declared node is. A single-story room only (the planner's heights are the ground
     * floor's). Seeded by the cell's seed, so a preview and its commit bury the same pockets.
     */
    static void placeHiddenOre(ServerLevel level, BlockPos origin, int spanY, Context ctx, long seed,
                               Set<BlockPos> nodesOut) {
        DungeonDef.HiddenOre hidden = ctx.hiddenOre();
        if (hidden == null || ctx.hiddenBlocks().isEmpty() || spanY > 1) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        HiddenOrePlanner.Rock solid = (x, y, z) -> {
            BlockState state = level.getBlockState(cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z));
            return state.isSolidRender() && !state.hasBlockEntity() && state.getFluidState().isEmpty();
        };
        HiddenOrePlanner.Rock free = (x, y, z) -> !nodesOut.contains(origin.offset(x, y, z));
        long planSeed = seed * 0x9E3779B97F4A7C15L + 0x6869646465L;
        List<List<int[]>> pockets = HiddenOrePlanner.plan(planSeed, hidden.pocketsMin(), hidden.pocketsMax(),
                hidden.sizeMin(), hidden.sizeMax(), RoomGeometry.CELL, RoomGeometry.WALL_HEIGHT, solid, free);
        for (int index = 0; index < pockets.size(); index++) {
            String block = ctx.hiddenBlocks().get(new Random(planSeed + 17L * index + 3L)
                    .nextInt(ctx.hiddenBlocks().size()));
            Identifier id = Identifier.tryParse(block);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                PocketDungeonsMod.LOG.warn("hiddenOre names an unknown block {}; pocket skipped", block);
                continue;
            }
            BlockState ore = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
            for (int[] at : pockets.get(index)) {
                BlockPos pos = origin.offset(at[0], at[1], at[2]);
                level.setBlock(pos, ore, FLAGS);
                nodesOut.add(pos.immutable());
            }
        }
    }

    /**
     * Dungeon structure W4: the affixes a door deals for the floor behind
     * {@code dungeonId} and {@code nodeId}, without Feral when that node is dark (a
     * dark floor and loose wolves do not mix). Applied to the dealt set only (the
     * level's seeded affixes and the node's signature); an affix a player chose with
     * a recipe is theirs to keep.
     */
    static Set<String> dealtAffixes(Set<String> affixes, String dungeonId, String nodeId) {
        if (affixes == null || affixes.isEmpty() || dungeonId == null || dungeonId.isEmpty()) {
            return affixes;
        }
        DungeonDefs dungeons = DungeonDefs.current();
        DungeonDef def = dungeons == null ? null : dungeons.byId(dungeonId);
        DungeonDef.Node node = def == null ? null : def.node(nodeId);
        if (node == null) {
            return affixes;
        }
        return DungeonLight.withoutFeralOnDark(affixes, node.light(), id -> {
            AffixDefinition definition = AffixManifest.current().byId(id);
            return AffixIds.FERAL.equals(id)
                    || (definition != null && definition.effects.neutralWolfSpawn);
        });
    }

    // ---- light ---------------------------------------------------------------

    private static void stripLight(ServerLevel level, BlockPos origin, DungeonRoomMeta meta, String light) {
        if (DungeonRoomMeta.LIGHT_LIT.equals(light)) {
            return;
        }
        int minY = meta.spanY > 1 ? -RoomGeometry.storyOffset(meta.spanY) : 0;
        List<int[]> lights = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int y = minY; y <= RoomGeometry.CEILING_Y; y++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (DungeonLight.isLightSource(pathOf(level.getBlockState(cursor)))) {
                        lights.add(new int[]{x, y, z});
                    }
                }
            }
        }
        for (int[] at : DungeonLight.removals(light, lights)) {
            BlockPos pos = origin.offset(at[0], at[1], at[2]);
            BlockState state = level.getBlockState(pos);
            BlockState replacement = DungeonLight.isSolidLight(pathOf(state))
                    ? solidNeighbour(level, pos) : Blocks.AIR.defaultBlockState();
            level.setBlock(pos, replacement, FLAGS);
        }
    }

    /**
     * A plain solid block to stand where a lamp block was: the first full cube found
     * among the lamp's neighbours (the ceiling around a ceiling lamp), else the shell
     * wall block.
     */
    private static BlockState solidNeighbour(ServerLevel level, BlockPos pos) {
        for (int reach = 1; reach <= 3; reach++) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockState state = level.getBlockState(pos.relative(dir, reach));
                if (state.isSolidRender() && !DungeonLight.isLightSource(pathOf(state))
                        && !state.hasBlockEntity()) {
                    return state;
                }
            }
        }
        return RoomBuilder.WALL;
    }

    // ---- nodes ---------------------------------------------------------------

    private static void placeDeclaredNodes(ServerLevel level, BlockPos origin, int quarterTurns,
                                           DungeonRoomMeta meta, long seed, Set<BlockPos> nodesOut) {
        int index = 0;
        for (DungeonRoomMeta.NodeSpec spec : meta.nodes) {
            Identifier id = Identifier.tryParse(spec.block());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                PocketDungeonsMod.LOG.warn("Room {} declares a node of unknown block {}; skipped",
                        meta.template, spec.block());
                index++;
                continue;
            }
            // PD-155: a group may not spawn at all; the roll is seeded per spec so
            // a preview and the commit stamp the same pockets.
            if (spec.chance() < 1 && new Random(seed * 31 + 7 + index).nextDouble() >= spec.chance()) {
                index++;
                continue;
            }
            BlockState declared = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
            for (int[] local : spec.positions(seed + 31L * index)) {
                int[] xz = DungeonRoomMeta.rotateLocal(local[0], local[2], quarterTurns);
                BlockPos pos = origin.offset(xz[0], local[1], xz[1]);
                BlockState there = level.getBlockState(pos);
                if (there.isAir()) {
                    level.setBlock(pos, declared, FLAGS);
                    nodesOut.add(pos.immutable());
                } else if (!there.hasBlockEntity() && there.getFluidState().isEmpty()) {
                    // The template already holds a block here (a theme may have
                    // remapped the one declared): it becomes the node as it stands.
                    nodesOut.add(pos.immutable());
                }
            }
            index++;
        }
    }

    private static void scanPaletteNodes(ServerLevel level, BlockPos origin, int spanY,
                                         Set<String> palette, Set<BlockPos> nodesOut) {
        if (palette.isEmpty()) {
            return;
        }
        int minY = spanY > 1 ? DungeonRoomMeta.NODE_MIN_Y : 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int y = minY; y <= DungeonRoomMeta.NODE_MAX_Y; y++) {
                for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (RoomProtection.isShell(cursor, origin)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (!state.isAir() && palette.contains(fullIdOf(state))) {
                        nodesOut.add(cursor.immutable());
                    }
                }
            }
        }
    }

    private static String fullIdOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String pathOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }
}
