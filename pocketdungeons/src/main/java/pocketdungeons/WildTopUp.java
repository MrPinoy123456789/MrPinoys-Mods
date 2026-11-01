package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Keeps the world stocked with wild lodestones (plan 4.2 and 4.3): every 100 ticks a few placement attempts when
 * fewer than {@code wildTargetActive} stand (several attempts per pass), always inside the world border. A candidate point comes from {@link WildPlacement}; it must keep its
 * distance from spawn and from every other lodestone, stay off claimed land, and pass the world checks (open dry
 * ground, not under a tree, no player building nearby). A rejected cell tries a different point next time. Overworld
 * only.
 */
final class WildTopUp {

    static final int EVERY = 100;
    /** Placement attempts per pass while below the target; rejected cells are common, so one try is too few. */
    static final int ATTEMPTS_PER_PASS = 6;
    /** Blocks kept between a lodestone and the world border. */
    static final int BORDER_MARGIN = 48;
    /** Blocks around a point that must be free of player built blocks (a 9 by 9 area). */
    static final int BUILDER_RADIUS = 4;
    /** Claim buffer in blocks: a 9 by 9 chunk neighbourhood at most. */
    static final int CLAIM_BUFFER = 64;

    static final TagKey<Block> BUILDER_BLOCKS =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "wild_builder_blocks"));

    private static final Random RANDOM = new Random();
    private static final Map<Long, Integer> GENERATION = SessionState.clearedAtStop(new HashMap<>());
    private static int counter;
    private static long lastClaimWarn;
    private static volatile ClaimSource.Snapshot claims;
    private static volatile long claimsModified = Long.MIN_VALUE;
    private static volatile long claimsCheckedAt;
    private static volatile boolean claimsBroken;

    private WildTopUp() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(WildTopUp::tick);
    }

    private static void tick(MinecraftServer server) {
        if (!WildMode.active() || ++counter % EVERY != 0) {
            return;
        }
        WildState state = WildStore.state();
        if (state == null || state.size() >= PocketDungeonsConfig.wildTargetActive()) {
            return;
        }
        for (int i = 0; i < ATTEMPTS_PER_PASS && state.size() < PocketDungeonsConfig.wildTargetActive(); i++) {
            attempt(server, server.overworld(), state);
        }
    }

    /** One placement attempt. Returns the lodestone placed, or {@code null}. Package private for the gametest. */
    static WildState.Lodestone attempt(MinecraftServer server, ServerLevel level, WildState state) {
        BlockPos spawn = server.overworld().getRespawnData().pos();
        int grid = PocketDungeonsConfig.wildGridSize();
        int radius = PocketDungeonsConfig.wildMaxRadius();
        // Sample only inside the world border, so no attempt is wasted outside it.
        net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
        int loX = Math.max(spawn.getX() - radius, (int) Math.ceil(border.getMinX()) + BORDER_MARGIN);
        int hiX = Math.min(spawn.getX() + radius, (int) Math.floor(border.getMaxX()) - BORDER_MARGIN);
        int loZ = Math.max(spawn.getZ() - radius, (int) Math.ceil(border.getMinZ()) + BORDER_MARGIN);
        int hiZ = Math.min(spawn.getZ() + radius, (int) Math.floor(border.getMaxZ()) - BORDER_MARGIN);
        if (hiX < loX || hiZ < loZ) {
            return null;
        }
        int cellX = WildPlacement.cellOf(loX + RANDOM.nextInt(hiX - loX + 1), grid);
        int cellZ = WildPlacement.cellOf(loZ + RANDOM.nextInt(hiZ - loZ + 1), grid);
        long key = ClaimSource.pack(cellX, cellZ);
        for (WildState.Lodestone existing : state.all()) {
            if (WildPlacement.cellOf(existing.x, grid) == cellX && WildPlacement.cellOf(existing.z, grid) == cellZ) {
                return null;
            }
        }
        int generation = GENERATION.getOrDefault(key, 0);
        int[] point = WildPlacement.candidate(level.getSeed(), cellX, cellZ, generation, grid);
        String rejection = rejection(server, level, state, point[0], point[1], spawn);
        if (rejection != null) {
            GENERATION.put(key, generation + 1);
            return null;
        }
        return place(level, state, point[0], point[1], spawn);
    }

    /** Why the point cannot hold a lodestone, or {@code null}. */
    static String rejection(MinecraftServer server, ServerLevel level, WildState state, int x, int z, BlockPos spawn) {
        if (!WildPlacement.withinRing(x, z, spawn.getX(), spawn.getZ(), PocketDungeonsConfig.wildMinSpawnDistance(),
                PocketDungeonsConfig.wildMaxRadius())) {
            return "outside the ring";
        }
        if (!level.getWorldBorder().isWithinBounds(x, z, BORDER_MARGIN)) {
            return "outside the world border";
        }
        List<int[]> others = new ArrayList<>();
        for (WildState.Lodestone l : state.all()) {
            others.add(new int[] {l.x, l.z});
        }
        if (!WildPlacement.separated(x, z, others, PocketDungeonsConfig.wildMinSeparation())) {
            return "too close to another lodestone";
        }
        String claimProblem = claimRejection(server, x, z);
        if (claimProblem != null) {
            return claimProblem;
        }
        return worldRejection(level, x, z);
    }

    private static String claimRejection(MinecraftServer server, int x, int z) {
        String source = PocketDungeonsConfig.wildClaimsSource();
        if (source == null || source.isBlank()) {
            return null;
        }
        refreshClaims(server, source.trim());
        ClaimSource.Snapshot snapshot = claims;
        if (claimsBroken || snapshot == null) {
            long now = System.currentTimeMillis();
            if (now - lastClaimWarn > 60_000L) {
                lastClaimWarn = now;
                PocketDungeonsMod.LOG.warn("The claims file {} is missing or unreadable; placing no wild lodestones", source);
            }
            return "claims unknown";
        }
        return snapshot.isClaimedNear(x, z, CLAIM_BUFFER) ? "claimed land" : null;
    }

    /** Reloads the claims file when its modified time changed, at most once a minute. */
    private static void refreshClaims(MinecraftServer server, String source) {
        long now = System.currentTimeMillis();
        if (now - claimsCheckedAt < 60_000L && (claims != null || claimsBroken)) {
            return;
        }
        claimsCheckedAt = now;
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(source);
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified != claimsModified || claims == null) {
                claims = ClaimSource.read(file);
                claimsModified = modified;
            }
            claimsBroken = false;
        } catch (IOException | RuntimeException e) {
            claimsBroken = true;
        }
    }

    /** Open, dry, treeless ground with no player building around it. Loads the chunks the area touches. */
    static String worldRejection(ServerLevel level, int x, int z) {
        for (int cx = (x - BUILDER_RADIUS) >> 4; cx <= (x + BUILDER_RADIUS) >> 4; cx++) {
            for (int cz = (z - BUILDER_RADIUS) >> 4; cz <= (z + BUILDER_RADIUS) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y != ground) {
            return "under leaves";
        }
        if (y < level.getSeaLevel() - 3) {
            return "too low";
        }
        BlockPos feet = new BlockPos(x, y, z);
        BlockState below = level.getBlockState(feet.below());
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()
                || below.is(BlockTags.LEAVES) || below.is(BlockTags.LOGS) || below.isAir()) {
            return "not dry open ground";
        }
        if (!level.canSeeSky(feet.above())) {
            return "no sky";
        }
        for (int dx = -BUILDER_RADIUS; dx <= BUILDER_RADIUS; dx++) {
            for (int dz = -BUILDER_RADIUS; dz <= BUILDER_RADIUS; dz++) {
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz);
                for (int dy = -2; dy <= 3; dy++) {
                    if (level.getBlockState(new BlockPos(x + dx, top + dy - 1, z + dz)).is(BUILDER_BLOCKS)) {
                        return "player built blocks";
                    }
                }
            }
        }
        return null;
    }

    /** Stamps the ruin and records the lodestone. The caller has already checked the point. */
    static WildState.Lodestone place(ServerLevel level, WildState state, int x, int z, BlockPos spawn) {
        double distance = Math.hypot((double) x - spawn.getX(), (double) z - spawn.getZ());
        WildDungeons.Offering offering = WildDungeons.pick(WildDungeons.all(), distance, RANDOM);
        if (offering == null) {
            return null;
        }
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        long now = WildClock.now();
        String id = state.nextId();
        WildState.Lodestone lodestone = new WildState.Lodestone(id, WildLodestones.dimensionKey(level), x, y, z,
                offering.id(), WildLevel.of(distance, offering.maxLevel()), now,
                WildLifespan.expiresAt(now, PocketDungeonsConfig.wildLifespanDays(), id));
        WildLodestones.stamp(level, pos);
        state.add(lodestone);
        WildStore.save();
        PocketDungeonsMod.LOG.info("Wild lodestone {} placed at {} ({}, level {})", id, pos.toShortString(),
                offering.id(), lodestone.level);
        return lodestone;
    }
}
