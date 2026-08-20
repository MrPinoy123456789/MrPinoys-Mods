package cobblebending;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks every block this mod places so it can be reverted instead of lost
 * on decay, recall, or shutdown.
 */
public final class BentBlocks {

    private static final Map<BlockPos, BentBlock> ALL = new ConcurrentHashMap<>();
    private static final Map<UUID, WallGroup> WALLS = new ConcurrentHashMap<>();
    private static int tickCounter;
    private static MinecraftServer server;

    private BentBlocks() {}

    public static void setServer(MinecraftServer s) {
        server = s;
    }

    /** Expires tracked blocks and refreshes bridge lifetimes while players stand on them. */
    public static void tick() {
        if (server == null) {
            return;
        }
        tickCounter++;
        Iterator<Map.Entry<BlockPos, BentBlock>> it = ALL.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BentBlock> e = it.next();
            BentBlock b = e.getValue();
            Level level = server.getLevel(b.dimension);
            if (level == null) {
                it.remove();
                continue;
            }
            if (b.bridge) {
                AABB blockBox = new AABB(b.pos);
                boolean stoodOn = false;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.level().dimension() == b.dimension && p.getBoundingBox().intersects(blockBox)) {
                        stoodOn = true;
                        break;
                    }
                }
                if (stoodOn) {
                    b.expiresAt = tickCounter + CobbleBendingMod.config().data().bridge().decayTicks();
                }
            }
            if (tickCounter >= b.expiresAt) {
                revert(level, b);
                it.remove();
                if (b.wallGroup != null) {
                    b.wallGroup.blocks.remove(b);
                }
            }
        }
        WALLS.entrySet().removeIf(e -> e.getValue().blocks.isEmpty());
    }

    /**
     * Places and tracks one temporary bridge block without replacing another bent block.
     *
     * @return one when placed, or zero when the position was already tracked
     */
    public static int placeBridge(ServerPlayer player, BlockPos pos, BlockState prior, int lifetime) {
        ResourceKey<Level> dim = player.level().dimension();
        if (ALL.putIfAbsent(pos, new BentBlock(pos, prior, player.getUUID(), dim, tickCounter, tickCounter + lifetime, true, 0, null)) != null) {
            return 0;
        }
        player.level().setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());
        enforceCap(player.getUUID());
        return 1;
    }

    /**
     * Replaces the player's previous wall and tracks every valid block in the new group.
     *
     * @return the number of blocks placed and therefore the cobblestone cost
     */
    public static int placeWall(ServerPlayer player, List<BlockStateSnapshot> placements, int lifetime) {
        UUID owner = player.getUUID();
        ServerLevel level = player.level();
        recallWall(player);

        List<BentBlock> groupBlocks = new ArrayList<>();
        int cost = 0;
        for (BlockStateSnapshot snap : placements) {
            if (ALL.containsKey(snap.pos()) || !isReplaceable(level, snap.pos())) {
                continue;
            }
            level.setBlockAndUpdate(snap.pos(), Blocks.COBBLESTONE.defaultBlockState());
            BentBlock b = new BentBlock(snap.pos(), snap.state(), owner, level.dimension(), tickCounter, tickCounter + lifetime, false, 1, null);
            ALL.put(snap.pos(), b);
            groupBlocks.add(b);
            cost++;
        }
        if (!groupBlocks.isEmpty()) {
            WallGroup group = new WallGroup(groupBlocks, cost);
            for (BentBlock b : groupBlocks) {
                b.wallGroup = group;
            }
            WALLS.put(owner, group);
        }
        enforceCap(owner);
        return cost;
    }

    /**
     * Restores all surviving blocks in the player's active wall.
     *
     * @return the configured partial cobblestone refund
     */
    public static int recallWall(ServerPlayer player) {
        WallGroup group = WALLS.remove(player.getUUID());
        if (group == null) {
            return 0;
        }
        for (BentBlock b : new ArrayList<>(group.blocks)) {
            Level level = server != null ? server.getLevel(b.dimension) : player.level();
            if (level != null) {
                revert(level, b);
            }
            ALL.remove(b.pos);
        }
        return (int) Math.floor(group.cost * CobbleBendingMod.config().data().wall().refundFraction());
    }

    public static boolean hasWall(ServerPlayer player) {
        WallGroup g = WALLS.get(player.getUUID());
        return g != null && !g.blocks.isEmpty();
    }

    /** Restores every tracked block in every loaded dimension and clears all groups. */
    public static void clearAll() {
        if (server == null) {
            return;
        }
        for (BentBlock b : new ArrayList<>(ALL.values())) {
            Level level = server.getLevel(b.dimension);
            if (level != null) {
                revert(level, b);
            }
        }
        ALL.clear();
        WALLS.clear();
    }

    /** Restores temporary world changes before the server stops. */
    public static void onShutdown() {
        clearAll();
    }

    public static boolean isReplaceable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced();
    }

    private static void revert(Level level, BentBlock b) {
        if (level.getBlockState(b.pos).is(Blocks.COBBLESTONE)) {
            level.setBlockAndUpdate(b.pos, b.prior);
        }
    }

    private static void enforceCap(UUID owner) {
        int cap = CobbleBendingMod.config().data().wall().trackedCap();
        List<BentBlock> owned = new ArrayList<>();
        for (BentBlock b : ALL.values()) {
            if (b.owner.equals(owner)) {
                owned.add(b);
            }
        }
        if (owned.size() <= cap) {
            return;
        }
        owned.sort((a, b) -> Integer.compare(a.placedAt, b.placedAt));
        int toRemove = owned.size() - cap;
        for (int i = 0; i < toRemove; i++) {
            BentBlock victim = owned.get(i);
            Level level = server != null ? server.getLevel(victim.dimension) : null;
            if (level != null) {
                revert(level, victim);
            }
            ALL.remove(victim.pos);
            if (victim.wallGroup != null) {
                victim.wallGroup.blocks.remove(victim);
            }
        }
    }

    private static final class BentBlock {
        final BlockPos pos;
        final BlockState prior;
        final UUID owner;
        final ResourceKey<Level> dimension;
        final int placedAt;
        int expiresAt;
        final boolean bridge;
        final int cost;
        WallGroup wallGroup;

        BentBlock(BlockPos pos, BlockState prior, UUID owner, ResourceKey<Level> dimension,
                  int placedAt, int expiresAt, boolean bridge, int cost, WallGroup wallGroup) {
            this.pos = pos;
            this.prior = prior;
            this.owner = owner;
            this.dimension = dimension;
            this.placedAt = placedAt;
            this.expiresAt = expiresAt;
            this.bridge = bridge;
            this.cost = cost;
            this.wallGroup = wallGroup;
        }
    }

    public record BlockStateSnapshot(BlockPos pos, BlockState state) {}

    private static final class WallGroup {
        final List<BentBlock> blocks;
        final int cost;

        WallGroup(List<BentBlock> blocks, int cost) {
            this.blocks = new ArrayList<>(blocks);
            this.cost = cost;
        }
    }
}
