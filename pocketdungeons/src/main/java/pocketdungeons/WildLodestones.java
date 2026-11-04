package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * The blocks of wild mode (decision 2026-10-30): the entrance ruin with its marker block in the overworld, and the Exit
 * markers inside a dungeon. A marker is a plain {@code minecraft:bedrock} block (hardness -1, blast proof), so players
 * cannot break one and explosions do nothing to it, and it carries no block entity, so there is nothing to edit.
 *
 * <p>It used to be a jigsaw block, but the vanilla client opens the jigsaw editor itself, on its own prediction, for an
 * operator in creative; the server cannot cancel that, whatever a use callback returns. A bedrock block gives the client
 * nothing to open. Java clients are shown a virtual {@code minecraft:jigsaw} over it ({@link WildMarkerDisplays}) when
 * Polymer is installed; Bedrock players see the bedrock. Wild jigsaws from earlier builds are converted when their
 * chunk loads, and the old click guard stays for any that are missed.
 *
 * <p>The saved {@link WildState}, not the block's name, says which lodestone an entrance belongs to. The exit blocks live
 * inside a record that is memory only, so their positions are kept in {@link #EXITS} and cleared at teardown.
 */
final class WildLodestones {

    /** Which lodestone an Exit block belongs to, and whether it is the clear exit. */
    record ExitRef(String lodestoneId, boolean clear) {}

    private static final Map<BlockPos, ExitRef> EXITS = SessionState.clearedAtStop(new HashMap<>());

    private WildLodestones() {}

    static void register() {
        UseBlockCallback.EVENT.register(WildLodestones::onUse);
        ServerChunkEvents.CHUNK_LOAD.register(WildLodestones::onChunkLoad);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> drainPendingMarkers());
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (POLYMER) {
                WildMarkerDisplays.dropChunk(level, chunk.getPos());
            }
        });
    }

    private record PendingMarker(ServerLevel level, BlockPos pos) {}

    private static final java.util.List<PendingMarker> PENDING_MARKERS = new java.util.ArrayList<>();

    /** Brings the markers whose chunk finished loading up to date, one tick after the load event. */
    static void drainPendingMarkers() {
        if (PENDING_MARKERS.isEmpty()) {
            return;
        }
        java.util.List<PendingMarker> now = new java.util.ArrayList<>(PENDING_MARKERS);
        PENDING_MARKERS.clear();
        for (PendingMarker m : now) {
            if (m.level().hasChunkAt(m.pos())) {
                ensureMarker(m.level(), m.pos());
            }
        }
    }

    /** Whether the Polymer virtual entity library is installed, so markers can show as jigsaws. */
    static final boolean POLYMER = FabricLoader.getInstance().isModLoaded("polymer-virtual-entity");

    static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        WildState state = WildStore.state();
        if (state == null || !WildMode.active()) {
            return;
        }
        String dimension = dimensionKey(level);
        for (WildState.Lodestone l : state.all()) {
            if (l.dimension.equals(dimension) && (l.x >> 4) == chunk.getPos().x() && (l.z >> 4) == chunk.getPos().z()) {
                // Not now: the chunk is still being finished, and asking the level for a block here waits on this very chunk
                // and hangs the server thread until the watchdog stops the server. The next tick is safe.
                PENDING_MARKERS.add(new PendingMarker(level, new BlockPos(l.x, l.y, l.z)));
            }
        }
    }

    /** After the state loads: fixes up every lodestone whose chunk is already loaded (the chunk events came first). */
    static void ensureAll(MinecraftServer server) {
        WildState state = WildStore.state();
        if (state == null) {
            return;
        }
        for (WildState.Lodestone l : state.all()) {
            for (ServerLevel level : server.getAllLevels()) {
                BlockPos pos = new BlockPos(l.x, l.y, l.z);
                if (dimensionKey(level).equals(l.dimension) && level.hasChunkAt(pos)) {
                    ensureMarker(level, pos);
                }
            }
        }
    }

    /**
     * Brings the entrance at {@code pos} up to date: a wild jigsaw from an earlier build becomes bedrock, and a bedrock
     * entrance gets its jigsaw lookalike if it has none.
     */
    static void ensureMarker(ServerLevel level, BlockPos pos) {
        if (isWildJigsaw(level, pos)) {
            level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
        }
        if (POLYMER && level.getBlockState(pos).is(Blocks.BEDROCK)) {
            WildMarkerDisplays.attach(level, pos);
        }
        WildState state = WildStore.state();
        WildState.Lodestone lodestone = state == null ? null
                : state.atEntrance(dimensionKey(level), pos.getX(), pos.getY(), pos.getZ());
        if (lodestone != null) {
            WildBanner.ensure(level, lodestone);
        }
    }

    static String dimensionKey(Level level) {
        return level.dimension().identifier().toString();
    }

    // ---- clicks -----------------------------------------------------------

    private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!WildMode.active() || level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        boolean marker = level.getBlockState(pos).is(Blocks.BEDROCK);
        if (!marker && !level.getBlockState(pos).is(Blocks.JIGSAW)) {
            return InteractionResult.PASS;
        }
        ExitRef exit = EXITS.get(pos);
        WildState state = WildStore.state();
        WildState.Lodestone entrance = state == null ? null
                : state.atEntrance(dimensionKey(level), pos.getX(), pos.getY(), pos.getZ());
        if (exit == null && entrance == null) {
            // A wild jigsaw from an earlier build that the server no longer tracks must still never open the vanilla
            // jigsaw editor. (A client may open it by itself anyway; the chunk load converts these to bedrock.)
            return !marker && isWildJigsaw(level, pos) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
        }
        // Both hands fire; only the main hand opens the screen, but neither may fall through to the vanilla jigsaw editor.
        if (hand == InteractionHand.MAIN_HAND) {
            if (entrance != null) {
                if (!WildOmen.tryApply(serverPlayer, (ServerLevel) level, pos, entrance)) {
                    WildDialogs.showOutside(serverPlayer, entrance);
                }
            } else {
                WildState.Lodestone owner = state == null ? null : state.get(exit.lodestoneId());
                if (owner != null) {
                    WildDialogs.showInside(serverPlayer, owner, exit.clear());
                }
            }
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Whether this jigsaw is one of ours: its name is {@code pocketdungeons:wild_*}. */
    static boolean isWildJigsaw(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof JigsawBlockEntity jigsaw
                && PocketDungeonsMod.MOD_ID.equals(jigsaw.getName().getNamespace())
                && jigsaw.getName().getPath().startsWith("wild_");
    }

    // ---- the entrance ruin --------------------------------------------------

    /**
     * Stamps the ruin: a 5 by 5 mossy brick floor, two broken pillars, a cobweb, and the entrance jigsaw at {@code pos}.
     * The space above the floor is cleared so the block is never buried. The placer guarantees an open sky column; an
     * operator placing by hand gets exactly where they stand.
     */
    static void stamp(ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean cracked = (Math.abs(dx) + Math.abs(dz)) % 2 == 1;
                level.setBlock(pos.offset(dx, -1, dz),
                        (cracked ? Blocks.CRACKED_STONE_BRICKS : Blocks.MOSSY_STONE_BRICKS).defaultBlockState(), 3);
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (int dy = 0; dy <= 2; dy++) {
            level.setBlock(pos.offset(-2, dy, -2), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }
        level.setBlock(pos.offset(2, 0, 2), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(pos.offset(2, 1, 2), Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(pos.offset(2, 2, -2), Blocks.COBWEB.defaultBlockState(), 3);
        placeMarker(level, pos);
    }

    /** Turns the ruin to rubble and removes the jigsaw (a collapse). */
    static void rubble(ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean gravel = (dx * 31 + dz * 17) % 3 == 0;
                level.setBlock(pos.offset(dx, -1, dz),
                        (gravel ? Blocks.GRAVEL : Blocks.COBBLESTONE).defaultBlockState(), 3);
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        if (POLYMER) {
            WildMarkerDisplays.detach(level, pos);
        }
        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(pos.offset(-2, 0, -2), Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(pos.offset(2, 0, 2), Blocks.COBBLESTONE_SLAB.defaultBlockState(), 3);
    }

    // ---- exit blocks ----------------------------------------------------------

    /** Places an Exit jigsaw inside a dungeon and remembers it for the click handler. */
    static void placeExit(ServerLevel level, BlockPos pos, String lodestoneId, boolean clear) {
        // Registered first, so the marker's display already knows it is an Exit.
        EXITS.put(pos.immutable(), new ExitRef(lodestoneId, clear));
        placeMarker(level, pos);
    }

    /** Forgets every Exit block of one lodestone (its dungeon is being torn down). */
    static void forgetExits(String lodestoneId) {
        EXITS.values().removeIf(ref -> ref.lodestoneId().equals(lodestoneId));
    }

    /** Whether an Exit block of this kind is registered for the lodestone. */
    static boolean hasExit(String lodestoneId, boolean clear) {
        for (ExitRef ref : EXITS.values()) {
            if (ref.lodestoneId().equals(lodestoneId) && ref.clear() == clear) {
                return true;
            }
        }
        return false;
    }

    static ExitRef exitAt(BlockPos pos) {
        return EXITS.get(pos);
    }

    /** What the marker at {@code pos} shows now: an Exit, or its lodestone's state. {@code null} when it is not ours. */
    static WildLook lookAt(ServerLevel level, BlockPos pos) {
        if (EXITS.containsKey(pos)) {
            return WildLook.EXIT;
        }
        WildState state = WildStore.state();
        WildState.Lodestone lodestone = state == null ? null
                : state.atEntrance(dimensionKey(level), pos.getX(), pos.getY(), pos.getZ());
        return lodestone == null ? null : WildLook.of(false, lodestone.phase, lodestone.ominous);
    }

    // ---- the marker block itself -------------------------------------------------

    /** A bedrock block with, for Java clients, a jigsaw shown over it. Replaces whatever stood at {@code pos}. */
    private static void placeMarker(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
        if (POLYMER) {
            WildMarkerDisplays.attach(level, pos);
        }
    }
}
