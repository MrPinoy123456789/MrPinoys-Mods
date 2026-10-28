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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * The raiders' strongholds (keeps) and outposts (camps) in the world (NPC strongholds). Each is an ordinary
 * faction with the {@code npc} flag, built with the same blocks and the same rules as a player's keep or camp:
 * a wall ring, a Nexus, a granary of chests, vanilla pillagers, vindicators and ravagers tagged
 * {@code charter.npc}. They are starved and razed like any keep and the plunder spills. A scheduler on the real
 * clock keeps the caps and replaces what fell elsewhere after {@code npcRespawnDays}.
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

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (CharterServer.running() && server.getTickCount() % PASS_TICKS == 0) runOnce(server, RNG);
        });
    }

    // ---- the scheduler ----

    /** One pass: collect what fell, then build at most one site. Returns what it did, for the admin command. */
    public static String runOnce(MinecraftServer server, Random rng) {
        CharterRuntime rt = CharterServer.runtime();
        ServerLevel level = server.overworld();
        long now = rt.clock.now();
        NpcBook book = rt.state.npc;
        for (NpcBook.Site site : NpcScheduler.collectFallen(book, rt.state, rt.knobs, now)) {
            cleanup(level, site);
        }
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
        if (kind == NpcBook.Kind.STRONGHOLD) buildStronghold(rt, level, core, at, tier);
        else buildOutpost(rt, level, core, at, tier);
        rt.claims.rebuild();
        NpcScheduler.built(rt.state.npc, new NpcBook.Site(rt.state.npc.nextId++, kind, org, core.id, center, tier, rt.clock.now()), replacement);
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
        defenders(level, nexus, rt.knobs.npcDefenders[tier], tier);
    }

    private static void buildOutpost(CharterRuntime rt, ServerLevel level, Core camp, BlockPos banner, int tier) {
        pad(level, banner, 3);
        level.setBlockAndUpdate(banner, ((BlockItem) LandItems.warBanner().getItem()).getBlock().defaultBlockState());
        Fixtures fixtures = Fixtures.of(rt.state);
        fixtures.add(new Fixtures.Fixture(Fixtures.Kind.BANNER, Protection.dimension(level), banner.getX(), banner.getY(), banner.getZ(), camp.id));
        BlockPos fire = Placement.raiseSignal(level, banner);
        if (fire != null) fixtures.add(new Fixtures.Fixture(Fixtures.Kind.CAMPFIRE, Protection.dimension(level), fire.getX(), fire.getY(), fire.getZ(), camp.id));
        rt.sim.supply().deposit("npc", camp.id, Map.of("minecraft:bread", storeBread(tier)), new ArrayList<>());
        defenders(level, banner, 1 + Math.min(2, tier), tier);
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

    /** Pillagers and vindicators, and a ravager for every fourth defender from the middle tier. They stay home and never count as attackers or members. */
    private static void defenders(ServerLevel level, BlockPos home, int count, int tier) {
        for (int i = 0; i < count; i++) {
            EntityType<? extends Mob> type = tier >= 1 && i % 4 == 3 ? EntityTypes.RAVAGER
                    : i % 2 == 0 ? EntityTypes.PILLAGER : EntityTypes.VINDICATOR;
            BlockPos at = home.offset((i % 3 - 1) * 3, 0, (i / 3) * 2 - 2);
            Mob mob = type.spawn(level, at, EntitySpawnReason.COMMAND);
            if (mob == null) continue;
            mob.addTag(TAG);
            mob.setPersistenceRequired();
            if (mob instanceof PathfinderMob p) p.setHomeTo(home, WALL_RADIUS + 2);
        }
    }

    // ---- what falls ----

    /** The defenders go with the site; an outpost leaves a small chest of its store where its banner stood. */
    static void cleanup(ServerLevel level, NpcBook.Site site) {
        BlockPos at = ground(level, site.chunk());
        AABB area = new AABB(at.getX() - 24, level.getMinY(), at.getZ() - 24, at.getX() + 24, level.getMaxY(), at.getZ() + 24);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area, m -> m.entityTags().contains(TAG))) mob.discard();
        if (site.kind() == NpcBook.Kind.OUTPOST) {
            level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
            if (level.getBlockEntity(at) instanceof Container box) {
                int left = storeBread(site.tier());
                for (int slot = 0; left > 0 && slot < box.getContainerSize(); slot++) {
                    int n = Math.min(64, left);
                    box.setItem(slot, new ItemStack(Items.BREAD, n));
                    left -= n;
                }
            }
        }
    }

    /** An operator removes a site: its world pieces, its faction and its place in the book (no replacement follows). */
    public static String clear(MinecraftServer server, long id) {
        CharterRuntime rt = CharterServer.runtime();
        ServerLevel level = server.overworld();
        Optional<NpcBook.Site> found = rt.state.npc.sites.stream().filter(s -> s.id() == id).findFirst();
        if (found.isEmpty()) return null;
        NpcBook.Site site = found.get();
        String name = rt.describe.org(site.org());
        rt.state.npc.sites.remove(site);
        GranaryService granaries = Granaries.service();
        for (Core core : List.copyOf(rt.state.coresOf(site.org()))) {
            SiegeWorld.physical(core.id, core.center, true, Map.of());
            if (granaries != null) granaries.book().remove(core.id);
            Fixtures.of(rt.state).removeCore(core.id);
        }
        BlockPos at = ground(level, site.chunk());
        AABB area = new AABB(at.getX() - 24, level.getMinY(), at.getZ() - 24, at.getX() + 24, level.getMaxY(), at.getZ() + 24);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area, m -> m.entityTags().contains(TAG))) mob.discard();
        rt.engine.run(new Actor.Admin("npc clear"), new charter.org.actions.DissolveOrg(site.org()));
        rt.claims.rebuild();
        return name;
    }
}
