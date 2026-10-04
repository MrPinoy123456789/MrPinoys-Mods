package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.spider.Spider;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * PD-110 (playtest 2026-10-02-1): spiders climb into the ceiling corners and
 * sit there. Vanilla spiders cling to any wall; in a cell's tight corner seam
 * the pathfinder finds no way back down while no one is in reach.
 *
 * <p>A dungeon spider that has been climbing, with no target, for
 * {@link #STUCK_TICKS} and is well above the floor is set back down on the
 * floor beneath it. A spider chasing a player is never touched, and nothing
 * outside the dungeon dimension is tracked. The check runs once a second.
 */
final class SpiderUnstick {

    /** Seconds of untargeted climbing before a spider is set down (5). */
    static final int STUCK_TICKS = 20 * 5;
    /** How far above the standing floor counts as "up the wall". */
    static final int CLIMB_HEIGHT = 3;
    private static final int CHECK_INTERVAL = 20;

    /** Spider to the tick it first looked stuck. Weak, so a removed spider drops out. */
    private static final Map<Spider, Long> STUCK_SINCE = new WeakHashMap<>();
    private static final Map<Spider, Boolean> TRACKED = new WeakHashMap<>();

    private SpiderUnstick() {}

    static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Spider spider && level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                synchronized (TRACKED) {
                    TRACKED.put(spider, Boolean.TRUE);
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % CHECK_INTERVAL != 0) {
                return;
            }
            long now = server.getTickCount();
            java.util.List<Spider> spiders;
            synchronized (TRACKED) {
                spiders = new java.util.ArrayList<>(TRACKED.keySet());
            }
            for (Spider spider : spiders) {
                check(spider, now);
            }
        });
    }

    private static void check(Spider spider, long now) {
        if (!spider.isAlive() || spider.isRemoved() || !(spider.level() instanceof ServerLevel level)) {
            STUCK_SINCE.remove(spider);
            return;
        }
        BlockPos floor = floorBelow(level, spider.blockPosition());
        boolean up = floor != null && spider.getBlockY() - floor.getY() > CLIMB_HEIGHT;
        if (!spider.isClimbing() || spider.getTarget() != null || !up) {
            STUCK_SINCE.remove(spider);
            return;
        }
        Long since = STUCK_SINCE.putIfAbsent(spider, now);
        if (since != null && now - since >= STUCK_TICKS) {
            STUCK_SINCE.remove(spider);
            spider.getNavigation().stop();
            spider.teleportTo(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5);
        }
    }

    /** The first solid block straight below {@code from}, or {@code null} within 32 blocks. */
    static BlockPos floorBelow(ServerLevel level, BlockPos from) {
        for (int dy = 1; dy <= 32; dy++) {
            BlockPos pos = from.below(dy);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return pos;
            }
        }
        return null;
    }
}
