package charter.mc.war;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.history.History;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.mc.land.Fixtures;
import charter.mc.land.Protection;
import charter.treasury.Currency;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's physical work on a core: spills items at a Nexus or breaks it, now if the chunk is loaded, else when
 * it loads (protected keeps: a keep no longer falls, so only the raiders' strongholds use this).
 *
 * <p>A core can be taken down with nobody near. Its physical consequences then wait for the chunk to load and
 * run on the tick after, never inside the load itself.
 */
public final class SiegeWorld {

    /** Physical work for a chunk that was not loaded when it was due. */
    private record Work(ChunkKey chunk, BlockPos at, boolean breakNexus, Map<Currency, Long> spill) {
    }

    private static final Map<ChunkKey, List<Work>> WAITING = new LinkedHashMap<>();
    /** Chunks with waiting work that loaded since the last tick. */
    private static final java.util.Set<ChunkKey> LOADED = new java.util.LinkedHashSet<>();

    private SiegeWorld() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!CharterServer.running()) return;
            CharterRuntime rt = CharterServer.runtime();
            SiegeLogFeed.attach(rt);
            WAITING.clear();
            LOADED.clear();
        });
        // Queued work waits for the tick after its chunk loads: placing blocks or spawning
        // items inside the load itself can hang the server thread on its own chunk.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            ChunkKey key = new ChunkKey(Protection.dimension(level), chunk.getPos().x(), chunk.getPos().z());
            if (WAITING.containsKey(key)) LOADED.add(key);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (LOADED.isEmpty()) return;
            List<ChunkKey> now = List.copyOf(LOADED);
            LOADED.clear();
            for (ChunkKey key : now) {
                ServerLevel level = levelOf(server, key.dimension());
                if (level == null || !level.hasChunk(key.x(), key.z())) continue;
                List<Work> due = WAITING.remove(key);
                if (due != null) for (Work work : due) apply(level, work);
            }
        });
    }

    // ---- the physical work ----

    /** Spills items at the nexus, or breaks it, now if the chunk is loaded, else when it loads. */
    public static void physical(CoreId keep, ChunkKey chunk, boolean breakNexus, Map<Currency, Long> spill) {
        CharterRuntime rt = CharterServer.runtime();
        Fixtures.Fixture nexus = Fixtures.of(rt.state).first(keep, Fixtures.Kind.NEXUS);
        ChunkKey where = nexus != null ? new ChunkKey(nexus.dimension(), nexus.x() >> 4, nexus.z() >> 4) : chunk;
        if (where == null) return;
        BlockPos at = nexus != null ? new BlockPos(nexus.x(), nexus.y(), nexus.z()) : null;
        Work work = new Work(where, at, breakNexus && nexus != null, spill);
        ServerLevel level = level(where.dimension());
        if (level != null && level.hasChunk(where.x(), where.z())) apply(level, work);
        else WAITING.computeIfAbsent(where, k -> new ArrayList<>()).add(work);
    }

    private static ServerLevel levelOf(net.minecraft.server.MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (Protection.dimension(level).equals(dimension)) return level;
        }
        return null;
    }

    private static void apply(ServerLevel level, Work work) {
        BlockPos at = work.at() != null ? work.at()
                : level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        new BlockPos(work.chunk().x() * 16 + 8, 0, work.chunk().z() * 16 + 8));
        if (work.breakNexus() && level.getBlockState(at).is(Blocks.LODESTONE)) {
            level.levelEvent(2001, at, net.minecraft.world.level.block.Block.getId(level.getBlockState(at)));
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        }
        for (Map.Entry<Currency, Long> e : work.spill().entrySet()) {
            var item = CharterServer.item(e.getKey().itemId);
            long left = e.getValue();
            while (left > 0) {
                int n = (int) Math.min(left, item.getDefaultMaxStackSize());
                ItemEntity drop = new ItemEntity(level, at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 0.5, new ItemStack(item, n));
                drop.setDefaultPickUpDelay();
                level.addFreshEntity(drop);
                left -= n;
            }
        }
    }

    /** How many physical changes wait for a chunk to load, for tests and the supply page. */
    public static int waiting() {
        return WAITING.values().stream().mapToInt(List::size).sum();
    }

    private static ServerLevel level(String dimension) {
        MinecraftServer server = CharterServer.server();
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (Protection.dimension(level).equals(dimension)) return level;
        }
        return null;
    }
}
