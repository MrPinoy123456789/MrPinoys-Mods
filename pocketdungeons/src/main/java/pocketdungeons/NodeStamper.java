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
    record Context(Set<String> palette, String nodeLight) {

        /** No dungeon behind the stamp (admin builds, Pocket2): declared nodes only, no darkening. */
        static final Context NONE = new Context(Set.of(), DungeonRoomMeta.LIGHT_LIT);

        Context {
            palette = Set.copyOf(palette);
            nodeLight = nodeLight == null ? DungeonRoomMeta.LIGHT_LIT : nodeLight;
        }
    }

    /**
     * The context for the floor behind {@code dungeonId} and {@code nodeId}; an
     * Endless Mine floor uses the mine dungeon's palette whatever door it came
     * through. An unknown dungeon gives {@link Context#NONE}.
     */
    static Context contextFor(String dungeonId, String nodeId, boolean mine) {
        DungeonDefs dungeons = DungeonDefs.current();
        DungeonDef def = dungeons == null ? null : dungeons.byId(mine ? ENDLESS_MINE : dungeonId);
        if (def == null) {
            return Context.NONE;
        }
        DungeonDef.Node node = mine ? null : def.node(nodeId);
        // A structural block in a palette (a third-party pack that skipped the validator) would
        // make walls mineable, so it is never a node.
        return new Context(def.nodePalette().stream().filter(b -> !DungeonDef.isStructuralBlock(b))
                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                node == null ? DungeonRoomMeta.LIGHT_LIT : node.light());
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
