package charter.mc.npc;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.land.Core;
import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.granary.GranaryService;
import charter.mc.granary.Granaries;
import charter.mc.land.Fixtures;
import charter.mc.land.LandItems;
import charter.mc.land.Placement;
import charter.mc.land.Protection;
import charter.mc.war.SiegeWorld;
import charter.npc.NpcBook;
import charter.npc.NpcPlacement;
import charter.npc.NpcScheduler;
import charter.npc.SpawnNpc;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * The raiders' strongholds (keeps) and outposts (camps) in the world (NPC strongholds). Each is an ordinary
 * faction with the {@code npc} flag, built with the same blocks and the same rules as a player's keep or camp:
 * a wall ring, a Nexus, a granary of chests, vanilla pillagers, vindicators and ravagers tagged
 * {@code charter.npc}. They are starved and razed like any keep and the plunder spills. A scheduler on the real
 * clock keeps the caps and replaces what fell elsewhere after {@code npcWaveMinutes}.
 *
 * <p>Protected keeps, N: a site has a supply and a pool. Every {@code npcWaveMinutes} it spawns raiders up to its
 * pool, each costing one supply. It falls when the supply is spent and none of its raiders lives, or when nobody
 * has fought it for {@code npcExpireDays}. When a site is raised it snapshots every block it replaces; when it
 * falls the snapshot is put back, so no ruins stand.
 *
 * <p>The raiders NEVER raid players in this version (N6): no NPC camp is planted against a player's keep. Camps
 * against player keeps come later with the Ragnarok season work; do not wire them in early.
 */
public final class NpcWorld {

    public static final String TAG = "charter.npc";
    private static final int PASS_TICKS = 200;
    private static final int MIN_RING = 16;
    private static final int MAX_RING = 250;
    private static final int WALL_RADIUS = 8;
    private static final Random RNG = new Random();

    private NpcWorld() {
    }

    /** Tests turn the automatic pass off and drive {@link #runOnce(MinecraftServer, Random, long)} themselves. */
    public static volatile boolean AUTO = true;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (AUTO && CharterServer.running() && server.getTickCount() % PASS_TICKS == 0) runOnce(server, RNG);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!CharterServer.running()) return;
            CharterRuntime rt = CharterServer.runtime();
            if (rt.state.npc.crumbles.isEmpty() || server.getTickCount() % Math.max(1, rt.knobs.npcCrumbleTicksPerLayer) != 0) return;
            for (NpcBook.Crumble c : List.copyOf(rt.state.npc.crumbles)) {
                if (crumbleStep(server.overworld(), c)) rt.state.npc.crumbles.remove(c);
            }
        });
    }

    // ---- the scheduler ----

    /** One pass on the real clock. */
    public static String runOnce(MinecraftServer server, Random rng) {
        return runOnce(server, rng, CharterServer.runtime().clock.now());
    }

    /**
     * One pass: sites whose keep fell to a siege are cleaned up; every standing site loses or gains raiders, falls or
     * expires; then at most one new site is built. Returns what it did, for the admin command.
     */
    public static String runOnce(MinecraftServer server, Random rng, long now) {
        CharterRuntime rt = CharterServer.runtime();
        ServerLevel level = server.overworld();
        NpcBook book = rt.state.npc;
        sweepFallen(server, now);
        for (NpcBook.Site site : List.copyOf(book.sites)) tend(server, rt, level, site, now);
        // A site that is still coming down owns its ground; nothing is built until it is gone.
        if (!book.crumbles.isEmpty()) return "a site is crumbling";
        Optional<NpcScheduler.Next> next = NpcScheduler.next(book, rt.knobs, now);
        if (next.isEmpty()) return "nothing to build";
        ChunkKey spawn = spawnChunk(level);
        List<ChunkKey> players = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            players.add(new ChunkKey(Protection.dimension(p.level()), p.getBlockX() >> 4, p.getBlockZ() >> 4));
        }
        Optional<ChunkKey> site = NpcPlacement.choose(rng, Protection.dimension(level), spawn, MIN_RING, MAX_RING,
                rt.state, rt.knobs, players, c -> terrainOk(level, c));
        if (site.isEmpty()) {
            CharterMod.LOG.info("The raiders found no site for a {}; trying again later", next.get().kind());
            return "no site found";
        }
        return raise(server, next.get().kind(), site.get(), next.get().fromTimer());
    }

    static ChunkKey spawnChunk(ServerLevel level) {
        BlockPos at = level.getRespawnData().pos();
        return new ChunkKey(Protection.dimension(level), at.getX() >> 4, at.getZ() >> 4);
    }

    /** Dry, generated and flat enough: nine samples across the footprint stand within five blocks of each other. */
    static boolean terrainOk(ServerLevel level, ChunkKey c) {
        int x0 = c.x() * 16 + 8;
        int z0 = c.z() * 16 + 8;
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int dx = -12; dx <= 12; dx += 12) {
            for (int dz = -12; dz <= 12; dz += 12) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + dx, z0 + dz);
                if (!level.getBlockState(new BlockPos(x0 + dx, y - 1, z0 + dz)).getFluidState().isEmpty()) return false;
                low = Math.min(low, y);
                high = Math.max(high, y);
            }
        }
        return high - low <= 5;
    }

    // ---- raising a site ----

    /** Founds the faction through the rules, then builds the site and its defenders. Returns a line for the operator. */
    public static String raise(MinecraftServer server, NpcBook.Kind kind, ChunkKey center, boolean replacement) {
        CharterRuntime rt = CharterServer.runtime();
        ServerLevel level = server.overworld();
        int tier = NpcPlacement.tier(spawnChunk(level), center, rt.knobs);
        String name = nextName(rt);
        long diamonds = kind == NpcBook.Kind.STRONGHOLD ? rt.knobs.npcDiamonds[tier] : 0;
        long cobblestone = kind == NpcBook.Kind.STRONGHOLD ? rt.knobs.npcCobblestone[tier] : 0;
        Outcome out = rt.engine.run(new Actor.System(), new SpawnNpc(name, Text.get("npc.banner"), kind, center, diamonds, cobblestone, replacement));
        if (out instanceof Outcome.Refused(Reason reason)) return reason.text();
        OrgId org = rt.state.orgNamed(name).orElseThrow().id;
        Core core = rt.state.coresOf(org).getFirst();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.getChunk(center.x() + dx, center.z() + dz);
        }
        BlockPos at = ground(level, center);
        Map<Long, BlockState> before = capture(level, at, kind == NpcBook.Kind.STRONGHOLD ? WALL_RADIUS + 3 : 6);
        if (kind == NpcBook.Kind.STRONGHOLD) buildStronghold(rt, level, core, at, tier);
        else buildOutpost(rt, level, core, at, tier);
        rt.claims.rebuild();
        NpcBook.Site site = new NpcBook.Site(rt.state.npc.nextId++, kind, org, core.id, center, tier, rt.clock.now());
        NpcScheduler.built(rt.state.npc, site, replacement);
        NpcBook.Live live = NpcScheduler.live(rt.state.npc, site, rt.knobs);
        live.hasHome = true;
        live.homeX = at.getX();
        live.homeY = at.getY();
        live.homeZ = at.getZ();
        live.adopted = true;
        live.snapshot = diff(level, before);
        spawnRaiders(level, site, live, NpcScheduler.wave(live, 0, rt.knobs, rt.clock.now()));
        return name + " raised a " + kind.name().toLowerCase(java.util.Locale.ROOT) + " at " + at.getX() + ", " + at.getZ() + " (tier " + tier + ")";
    }

    private static String nextName(CharterRuntime rt) {
        for (int i = 1; Text.has("npc.name." + i); i++) {
            String name = Text.get("npc.name." + i);
            if (rt.state.orgNamed(name).isEmpty()) return name;
        }
        for (int n = 2; ; n++) {
            String name = Text.get("npc.name.1") + " " + n;
            if (rt.state.orgNamed(name).isEmpty()) return name;
        }
    }

    private static BlockPos ground(ServerLevel level, ChunkKey chunk) {
        level.getChunk(chunk.x(), chunk.z());
        int x = chunk.x() * 16 + 8;
        int z = chunk.z() * 16 + 8;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    private static void pad(ServerLevel level, BlockPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos at = centre.offset(dx, 0, dz);
                level.setBlockAndUpdate(at.below(), Blocks.STONE_BRICKS.defaultBlockState());
                for (int dy = 0; dy < 6; dy++) level.setBlockAndUpdate(at.above(dy), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void buildStronghold(CharterRuntime rt, ServerLevel level, Core keep, BlockPos nexus, int tier) {
        pad(level, nexus, WALL_RADIUS + 1);
        for (int d = -WALL_RADIUS; d <= WALL_RADIUS; d++) {
            for (int dy = 0; dy < 4; dy++) {
                level.setBlockAndUpdate(nexus.offset(d, dy, -WALL_RADIUS), Blocks.COBBLESTONE.defaultBlockState());
                level.setBlockAndUpdate(nexus.offset(-WALL_RADIUS, dy, d), Blocks.COBBLESTONE.defaultBlockState());
                level.setBlockAndUpdate(nexus.offset(WALL_RADIUS, dy, d), Blocks.COBBLESTONE.defaultBlockState());
                // The gate: three wide, open, on the south side.
                if (Math.abs(d) > 1) level.setBlockAndUpdate(nexus.offset(d, dy, WALL_RADIUS), Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(nexus, Blocks.LODESTONE.defaultBlockState());
        Fixtures.of(rt.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, Protection.dimension(level), nexus.getX(), nexus.getY(), nexus.getZ(), keep.id));
        granary(rt, level, keep, nexus, rt.knobs.npcGranaryShare[tier]);
    }

    private static void buildOutpost(CharterRuntime rt, ServerLevel level, Core camp, BlockPos banner, int tier) {
        pad(level, banner, 3);
        level.setBlockAndUpdate(banner, ((BlockItem) LandItems.warBanner().getItem()).getBlock().defaultBlockState());
        Fixtures fixtures = Fixtures.of(rt.state);
        fixtures.add(new Fixtures.Fixture(Fixtures.Kind.BANNER, Protection.dimension(level), banner.getX(), banner.getY(), banner.getZ(), camp.id));
        BlockPos fire = Placement.raiseSignal(level, banner);
        if (fire != null) fixtures.add(new Fixtures.Fixture(Fixtures.Kind.CAMPFIRE, Protection.dimension(level), fire.getX(), fire.getY(), fire.getZ(), camp.id));
        rt.sim.supply().deposit("npc", camp.id, Map.of("minecraft:bread", storeBread(tier)), new ArrayList<>());
    }

    /** A stack or two of bread in an outpost's store, more further out. */
    static int storeBread(int tier) {
        return 64 + 32 * tier;
    }

    private static void granary(CharterRuntime rt, ServerLevel level, Core keep, BlockPos nexus, double share) {
        GranaryService service = Granaries.service();
        if (service == null) return;
        double limit = rt.sim.rules().storageLimit(keep);
        double bread = rt.sim.food().get("minecraft:bread").nourishment();
        int loaves = (int) Math.ceil(Math.max(0, Math.min(1, share)) * limit / bread);
        int perChest = 27 * 64;
        int chests = Math.max(1, Math.min(rt.knobs.granaryMaxChests, (loaves + perChest - 1) / perChest));
        for (int i = 0; i < chests; i++) {
            BlockPos at = nexus.offset(2, 0, i - 1);
            level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
            if (service.link(keep, level, at, false, true) != null) continue;
            Container box = (Container) level.getBlockEntity(at);
            int put = Math.min(perChest, loaves);
            for (int slot = 0; put > 0 && slot < box.getContainerSize(); slot++) {
                int n = Math.min(64, put);
                box.setItem(slot, new ItemStack(Items.BREAD, n));
                put -= n;
                loaves -= n;
            }
        }
        service.reconcile(service.book().site(keep.id), null);
    }

    // ---- raiders ----

    /** Pillagers and vindicators, and a ravager for every fourth raider from the middle tier. They stay home and never count as attackers or members. */
    private static void spawnRaiders(ServerLevel level, NpcBook.Site site, NpcBook.Live live, int count) {
        BlockPos home = new BlockPos(live.homeX, live.homeY, live.homeZ);
        for (int n = 0; n < count; n++) {
            int i = live.spawned++;
            EntityType<? extends Mob> type = site.tier() >= 1 && i % 4 == 3 ? EntityTypes.RAVAGER
                    : i % 2 == 0 ? EntityTypes.PILLAGER : EntityTypes.VINDICATOR;
            BlockPos at = home.offset((i % 3 - 1) * 3, 0, ((i / 3) % 3) * 2 - 2);
            Mob mob = type.spawn(level, at, EntitySpawnReason.COMMAND);
            if (mob == null) continue;
            mob.addTag(TAG);
            mob.setPersistenceRequired();
            if (mob instanceof PathfinderMob p) p.setHomeTo(home, WALL_RADIUS + 2);
            live.raiders.add(mob.getUUID());
        }
    }

    private static BlockPos homeOf(CharterRuntime rt, ServerLevel level, NpcBook.Site site, NpcBook.Live live) {
        if (live != null && live.hasHome) return new BlockPos(live.homeX, live.homeY, live.homeZ);
        Fixtures fixtures = Fixtures.of(rt.state);
        for (Core core : rt.state.coresOf(site.org())) {
            Fixtures.Fixture f = fixtures.first(core.id, Fixtures.Kind.NEXUS);
            if (f == null) f = fixtures.first(core.id, Fixtures.Kind.BANNER);
            if (f != null) return new BlockPos(f.x(), f.y(), f.z());
        }
        return ground(level, site.chunk());
    }

    private static List<Mob> tagged(ServerLevel level, BlockPos home) {
        AABB area = new AABB(home.getX() - 24, level.getMinY(), home.getZ() - 24, home.getX() + 24, level.getMaxY(), home.getZ() + 24);
        return level.getEntitiesOfClass(Mob.class, area, m -> m.entityTags().contains(TAG));
    }

    /** Cleans up every site whose keep or camp was lost to a siege. */
    public static void sweepFallen(MinecraftServer server, long now) {
        CharterRuntime rt = CharterServer.runtime();
        for (NpcBook.Site site : NpcScheduler.collectFallen(rt.state.npc, rt.state, rt.knobs, now)) {
            dismantle(server, site, false, false, true, now);
        }
    }

    /** One standing site on one pass, at a time of the caller's choosing (tests drive a single site this way). */
    public static void tendSite(MinecraftServer server, NpcBook.Site site, long now) {
        CharterRuntime rt = CharterServer.runtime();
        tend(server, rt, server.overworld(), site, now);
    }

    /** One standing site on one pass: count its raiders, then let the scheduler decide. */
    private static void tend(MinecraftServer server, CharterRuntime rt, ServerLevel level, NpcBook.Site site, long now) {
        NpcBook.Live live = NpcScheduler.live(rt.state.npc, site, rt.knobs);
        BlockPos home = homeOf(rt, level, site, live);
        if (!live.hasHome) {
            live.hasHome = true;
            live.homeX = home.getX();
            live.homeY = home.getY();
            live.homeZ = home.getZ();
        }
        if (!level.hasChunkAt(home) || !level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(home.getX() >> 4, home.getZ() >> 4))) {
            // Nothing can be counted in a chunk nobody has loaded; only the failsafe is judged.
            if (now - live.lastFoughtAt >= charter.time.Durations.days(rt.knobs.npcExpireDays)) dismantle(server, site, false, true, true, now);
            return;
        }
        if (!live.adopted) {
            for (Mob mob : tagged(level, home)) if (!live.raiders.contains(mob.getUUID())) live.raiders.add(mob.getUUID());
            live.adopted = true;
        }
        int died = 0;
        for (UUID id : List.copyOf(live.raiders)) {
            Entity e = level.getEntity(id);
            if (e == null || !e.isAlive()) {
                live.raiders.remove(id);
                died++;
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == level && Math.abs(p.getBlockX() - home.getX()) <= 48 && Math.abs(p.getBlockZ() - home.getZ()) <= 48) live.lastFoughtAt = now;
        }
        NpcScheduler.Step step = NpcScheduler.step(live, live.raiders.size(), died, rt.knobs, now);
        if (step.fell()) dismantle(server, site, true, true, true, now);
        else if (step.expired()) dismantle(server, site, false, true, true, now);
        else if (step.spawn() > 0) spawnRaiders(level, site, live, step.spawn());
    }

    // ---- what the site changed ----

    /** Every block state in the box around a site, before anything is built. */
    private static Map<Long, BlockState> capture(ServerLevel level, BlockPos at, int half) {
        Map<Long, BlockState> before = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-half, -3, -half), at.offset(half, 8, half))) {
            before.put(pos.asLong(), level.getBlockState(pos));
        }
        return before;
    }

    /** The positions in the box that differ now, with the state each had before. */
    private static NpcBook.Snapshot diff(ServerLevel level, Map<Long, BlockState> before) {
        NpcBook.Snapshot snapshot = new NpcBook.Snapshot();
        Map<String, Integer> index = new HashMap<>();
        for (Map.Entry<Long, BlockState> e : before.entrySet()) {
            BlockPos pos = BlockPos.of(e.getKey());
            if (level.getBlockState(pos) == e.getValue()) continue;
            String state = BlockStateParser.serialize(e.getValue());
            int i = index.computeIfAbsent(state, k -> {
                snapshot.states.add(k);
                return snapshot.states.size() - 1;
            });
            snapshot.blocks.add(new int[]{pos.getX(), pos.getY(), pos.getZ(), i});
        }
        return snapshot;
    }

    /** Puts every recorded block back. Containers are emptied first so nothing spills. */
    static void restore(ServerLevel level, NpcBook.Snapshot snapshot) {
        List<BlockState> states = new ArrayList<>();
        for (String text : snapshot.states) {
            try {
                states.add(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState());
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                states.add(Blocks.AIR.defaultBlockState());
            }
        }
        for (int[] b : snapshot.blocks) {
            BlockPos pos = new BlockPos(b[0], b[1], b[2]);
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (level.getBlockEntity(pos) instanceof Container box) box.clearContent();
        }
        for (int[] b : snapshot.blocks) {
            level.setBlock(new BlockPos(b[0], b[1], b[2]), states.get(b[3]), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
        }
    }

    /**
     * What a site built and nothing else leaves behind: its cobblestone, stone bricks, Nexus, chests and banner within
     * {@code radius} of the centre. Stone bricks give way to grass. Used when a site has no snapshot (an old save) and by
     * {@code admin npc clearruins}. Returns how many blocks it cleared.
     */
    public static int clearPalette(ServerLevel level, int cx, int cz, int radius, int yMin, int yMax) {
        Block banner = ((BlockItem) LandItems.warBanner().getItem()).getBlock();
        int cleared = 0;
        for (int y = yMax; y >= yMin; y--) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    Block block = state.getBlock();
                    boolean palette = block == Blocks.COBBLESTONE || block == Blocks.LODESTONE || block == Blocks.CHEST
                            || block == Blocks.CAMPFIRE || block == banner;
                    if (block == Blocks.STONE_BRICKS) {
                        boolean open = level.getBlockState(pos.above()).isAir();
                        level.setBlock(pos, (open ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        cleared++;
                    } else if (palette) {
                        if (level.getBlockEntity(pos) instanceof Container box) box.clearContent();
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }

    // ---- what falls ----

    /**
     * Takes a site down: its raiders, its cores, fixtures and granary, its faction and its claim, and the ground put back
     * from the snapshot (or, with none, cleared to the surface). {@code loot} spills a share of its treasury where it
     * stood once the ground is back; {@code retire} also awaits a replacement; {@code slowly} lets the blocks come down a
     * layer at a time (an operator's clear is instant). Returns the faction's name.
     */
    static String dismantle(MinecraftServer server, NpcBook.Site site, boolean loot, boolean retire, boolean slowly, long now) {
        CharterRuntime rt = CharterServer.runtime();
        ServerLevel level = server.overworld();
        NpcBook book = rt.state.npc;
        NpcBook.Live live = book.live.get(site.id());
        BlockPos home = homeOf(rt, level, site, live);
        String name = rt.describe.org(site.org());
        long[] share = loot ? lootShare(rt, site) : new long[]{0, 0};
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.getChunk(site.chunk().x() + dx, site.chunk().z() + dz);
        }
        if (live != null) {
            for (UUID id : live.raiders) {
                Entity e = level.getEntity(id);
                if (e != null) e.discard();
            }
        }
        for (Mob mob : tagged(level, home)) mob.discard();
        GranaryService granaries = Granaries.service();
        for (Core core : List.copyOf(rt.state.coresOf(site.org()))) {
            SiegeWorld.physical(core.id, core.center, true, Map.of());
            if (granaries != null) granaries.book().remove(core.id);
            Fixtures.of(rt.state).removeCore(core.id);
        }
        if (rt.state.org(site.org()).isPresent()) {
            rt.engine.run(new Actor.Admin("npc dismantle"), new charter.org.actions.DissolveOrg(site.org()));
        }
        rt.claims.rebuild();
        NpcBook.Snapshot ground = live != null && live.snapshot != null ? live.snapshot : paletteSnapshot(level, home);
        NpcBook.Crumble crumble = new NpcBook.Crumble();
        crumble.dimension = Protection.dimension(level);
        crumble.restore = ground;
        crumble.homeX = home.getX();
        crumble.homeY = home.getY();
        crumble.homeZ = home.getZ();
        crumble.diamonds = share[0];
        crumble.cobblestone = share[1];
        // Only the positions the site changed, and only those that now hold something, come down layer by layer.
        for (int[] b : ground.blocks) {
            if (!level.getBlockState(new BlockPos(b[0], b[1], b[2])).isAir()) crumble.pending.add(new int[]{b[0], b[1], b[2]});
        }
        crumble.pending.sort(java.util.Comparator.comparingInt(p -> p[1]));
        book.crumbles.add(crumble);
        if (!slowly) {
            while (!crumbleStep(level, crumble)) { }
            book.crumbles.remove(crumble);
        }
        if (retire) {
            NpcScheduler.retire(book, site, rt.knobs, now);
        } else {
            book.sites.remove(site);
            book.live.remove(site.id());
        }
        return name;
    }

    /** A quarter of the treasury's diamonds and of its cobblestone. */
    private static long[] lootShare(CharterRuntime rt, NpcBook.Site site) {
        var org = rt.state.org(site.org());
        if (org.isEmpty()) return new long[]{0, 0};
        return new long[]{org.get().ledger.balance(charter.treasury.Currency.DIAMOND) / 4,
                org.get().ledger.balance(charter.treasury.Currency.COBBLESTONE) / 4};
    }

    /** As at most {@code npcFallLoot} stacks, diamonds first. */
    private static List<ItemStack> lootStacks(CharterRuntime rt, long diamonds, long cobble) {
        List<ItemStack> stacks = new ArrayList<>();
        while (stacks.size() < rt.knobs.npcFallLoot && (diamonds > 0 || cobble > 0)) {
            if (diamonds > 0) {
                int n = (int) Math.min(64, diamonds);
                stacks.add(new ItemStack(Items.DIAMOND, n));
                diamonds -= n;
            } else {
                int n = (int) Math.min(64, cobble);
                stacks.add(new ItemStack(Items.COBBLESTONE, n));
                cobble -= n;
            }
        }
        return stacks;
    }

    /** For a site raised before snapshots: what to clear (its palette blocks within reach) and what to leave in their place. */
    private static NpcBook.Snapshot paletteSnapshot(ServerLevel level, BlockPos home) {
        NpcBook.Snapshot snapshot = new NpcBook.Snapshot();
        snapshot.states.add(BlockStateParser.serialize(Blocks.AIR.defaultBlockState()));
        snapshot.states.add(BlockStateParser.serialize(Blocks.GRASS_BLOCK.defaultBlockState()));
        Block banner = ((BlockItem) LandItems.warBanner().getItem()).getBlock();
        int r = WALL_RADIUS + 2;
        for (int y = home.getY() + 8; y >= home.getY() - 4; y--) {
            for (int x = home.getX() - r; x <= home.getX() + r; x++) {
                for (int z = home.getZ() - r; z <= home.getZ() + r; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) continue;
                    Block block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.STONE_BRICKS) snapshot.blocks.add(new int[]{x, y, z, 1});
                    else if (block == Blocks.COBBLESTONE || block == Blocks.LODESTONE || block == Blocks.CHEST
                            || block == Blocks.CAMPFIRE || block == banner) snapshot.blocks.add(new int[]{x, y, z, 0});
                }
            }
        }
        return snapshot;
    }

    /**
     * One layer of a crumble: the top layer of what is left comes down with break particles and a sound, containers
     * emptied first. When nothing is left the ground is put back and the loot spills. Returns true when it is over.
     */
    static boolean crumbleStep(ServerLevel level, NpcBook.Crumble c) {
        if (!c.pending.isEmpty()) {
            int top = c.pending.get(c.pending.size() - 1)[1];
            int shown = 0;
            while (!c.pending.isEmpty() && c.pending.get(c.pending.size() - 1)[1] == top) {
                int[] p = c.pending.remove(c.pending.size() - 1);
                BlockPos pos = new BlockPos(p[0], p[1], p[2]);
                level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                BlockState old = level.getBlockState(pos);
                if (old.isAir()) continue;
                if (level.getBlockEntity(pos) instanceof Container box) box.clearContent();
                if (shown < 10 && ((p[0] + p[2]) & 3) == 0) {
                    level.levelEvent(2001, pos, Block.getId(old));
                    shown++;
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            }
            return false;
        }
        restore(level, c.restore);
        CharterRuntime rt = CharterServer.runtime();
        for (ItemStack stack : lootStacks(rt, c.diamonds, c.cobblestone)) {
            level.addFreshEntity(new ItemEntity(level, c.homeX + 0.5, c.homeY + 1, c.homeZ + 0.5, stack));
        }
        return true;
    }

    /** An operator removes a site: its world pieces, its faction and its place in the book (no replacement follows). */
    public static String clear(MinecraftServer server, long id) {
        CharterRuntime rt = CharterServer.runtime();
        Optional<NpcBook.Site> found = rt.state.npc.sites.stream().filter(x -> x.id() == id).findFirst();
        if (found.isEmpty()) return null;
        return dismantle(server, found.get(), false, false, false, rt.clock.now());
    }
}
