package charter.mc.war;

import charter.contract.JusticeInputs;
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
import charter.siege.FallChoice;
import charter.siege.SiegeEvents;
import charter.siege.actions.ChooseFall;
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
 * M11: the siege in the world. Feeds presence and prisoners into the sim before
 * each step; when a keep falls, asks the besieger to raze or occupy with two
 * clickable choices; spills the plunder as real items at the nexus; and breaks the
 * nexus when a keep is razed. The granary becomes loot by itself, since the keep's
 * claims are gone and its chests are ordinary chests again.
 *
 * <p>A keep can fall with nobody near. Its physical consequences then wait for the
 * chunk to load (PLAN.md §4.5): the fall is recorded at once, the spill and the
 * broken nexus happen when someone comes. The wait list is not saved; a restart in
 * between leaves the nexus standing, as an ordinary lodestone with no keep behind it.
 */
public final class SiegeWorld {

    /** Physical work for a chunk that was not loaded when it was due. */
    private record Work(ChunkKey chunk, BlockPos at, boolean breakNexus, Map<Currency, Long> spill) {
    }

    private static final WorldInputs PRESENCE = new WorldInputs();
    private static final Map<ChunkKey, List<Work>> WAITING = new LinkedHashMap<>();
    /** Chunks with waiting work that loaded since the last tick. */
    private static final java.util.Set<ChunkKey> LOADED = new java.util.LinkedHashSet<>();

    private SiegeWorld() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!CharterServer.running()) return;
            CharterRuntime rt = CharterServer.runtime();
            rt.sim.inputs(JusticeInputs.wrap(PRESENCE, rt.state, rt.knobs, rt.clock::now));
            rt.state.history.listen(SiegeWorld::onHistory);
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

    /** Counts presence; called by the server just before each sim step. */
    public static void beforeStep(MinecraftServer server, CharterRuntime rt) {
        PRESENCE.refresh(server, rt);
    }

    /** The presence counter, for tests. */
    public static WorldInputs presence() {
        return PRESENCE;
    }

    // ---- history ----

    static void onHistory(History.Entry entry) {
        if (!CharterServer.running()) return;
        switch (entry.event()) {
            case SiegeEvents.KeepFell e -> offerChoice(e.keep(), e.besieger());
            case SiegeEvents.Plundered e -> {
                if (!e.amounts().isEmpty()) physical(e.keep(), e.at_chunk(), false, e.amounts());
            }
            case SiegeEvents.Razed e -> physical(e.keep(), null, true, Map.of());
            default -> {
            }
        }
    }

    /** Sends every online member of the besieging faction who may choose two clickable choices. */
    static void offerChoice(CoreId keep, OrgId besieger) {
        CharterRuntime rt = CharterServer.runtime();
        MinecraftServer server = CharterServer.server();
        if (server == null) return;
        OrgId faction = rt.state.root(besieger);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerId id = new PlayerId(player.getUUID());
            boolean ours = rt.state.membership(id).map(m -> rt.state.root(m.org()).equals(faction)).orElse(false);
            if (!ours) continue;
            boolean may = new AuthorityResolver(rt.state, rt.clock.now())
                    .resolve(new Who.AsPlayer(id), besieger, Power.CHOOSE_FALL, ScopeNeed.NONE, false).ok();
            if (!may) continue;
            Component line = Chat.line(Text.get("war.fall_choose", "site", rt.describe.core(keep)) + " ")
                    .append(Chat.act(player, Text.get("war.raze"), Text.get("war.raze_hover"), p -> choose(p, besieger, keep, FallChoice.RAZE)))
                    .append(Component.literal("  "))
                    .append(Chat.act(player, Text.get("war.occupy"), Text.get("war.occupy_hover"), p -> choose(p, besieger, keep, FallChoice.OCCUPY)));
            player.sendSystemMessage(line);
        }
    }

    public static void choose(ServerPlayer player, OrgId besieger, CoreId keep, FallChoice choice) {
        CharterRuntime rt = CharterServer.runtime();
        Outcome outcome = rt.engine.run(new Actor.Player(new PlayerId(player.getUUID())), new ChooseFall(besieger, keep, choice));
        String text = outcome.done()
                ? Text.get(choice == FallChoice.RAZE ? "war.razed_you" : "war.occupied_you", "site", rt.describe.core(keep))
                : Text.get("war.choice_refused");
        player.sendSystemMessage(Component.literal(text).withStyle(outcome.done() ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    // ---- the physical fall ----

    /** Spills plunder at the nexus, or breaks it, now if the chunk is loaded, else when it loads. */
    static void physical(CoreId keep, ChunkKey chunk, boolean breakNexus, Map<Currency, Long> spill) {
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
