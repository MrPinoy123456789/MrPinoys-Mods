package unstick;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The watchdog. Every {@link #TICK_INTERVAL} ticks, scans loaded villagers.
 * For each one that is actively navigating, measures net horizontal displacement
 * from the start of its current window. If it has not covered {@code
 * moveThreshold} blocks in {@code stuckTicks} of trying, vanilla's pathfinder
 * has wedged it (the MC-96319 family: flower pots, lanterns, carpets under low
 * ceilings, trapdoor corners, and so on). A small teleport nudge toward the
 * path target clears the obstruction, the path is dropped so vanilla re-paths
 * from the new position, and a cooldown stops it being re-nudged before that
 * re-path has had a chance to land.
 *
 * <p>Routing is never changed. The villager still wants to go where vanilla
 * wanted it to go; Unstick just gets it past the block it could not step over.
 */
public final class StuckWatcher {

    private static final int TICK_INTERVAL = 10;

    private static final Map<UUID, Track> tracks = new ConcurrentHashMap<>();
    private static int tickCounter;

    private StuckWatcher() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % TICK_INTERVAL != 0) {
                return;
            }
            Set<UUID> seen = new HashSet<>();
            for (ServerLevel level : server.getAllLevels()) {
                List<? extends Villager> villagers = level.getEntities(
                        EntityTypeTest.<net.minecraft.world.entity.Entity, Villager>forClass(Villager.class),
                        v -> true);
                for (Villager villager : villagers) {
                    seen.add(villager.getUUID());
                    tick(villager, level);
                }
            }
            // Drop trackers for villagers that have unloaded or died, so the
            // map does not grow without bound over a long save.
            tracks.keySet().removeIf(uuid -> !seen.contains(uuid));
        });
    }

    private static void tick(Villager villager, ServerLevel level) {
        Track track = tracks.computeIfAbsent(villager.getUUID(), u -> new Track(villager.getX(), villager.getZ()));

        if (track.cooldown > 0) {
            track.cooldown -= TICK_INTERVAL;
            // Keep the window anchored where the villager will be once the
            // cooldown lifts, so the first post-cooldown sample is fair.
            track.startX = villager.getX();
            track.startZ = villager.getZ();
            track.stuckTicks = 0;
            return;
        }

        // Skip states where "not moving" is correct behaviour, not a wedge.
        if (villager.isPassenger() || villager.isVehicle() || villager.isSleeping()
                || villager.isInWater() || !villager.getNavigation().isInProgress()) {
            track.startX = villager.getX();
            track.startZ = villager.getZ();
            track.stuckTicks = 0;
            return;
        }

        double dx = villager.getX() - track.startX;
        double dz = villager.getZ() - track.startZ;
        double netHorizontal = Math.sqrt(dx * dx + dz * dz);

        if (netHorizontal >= UnstickConfig.moveThreshold()) {
            // Genuinely making progress. Re-anchor the window here and restart
            // the count. A villager that inches forward every few seconds will
            // keep resetting and never trip the rescue.
            track.startX = villager.getX();
            track.startZ = villager.getZ();
            track.stuckTicks = 0;
            return;
        }

        track.stuckTicks += TICK_INTERVAL;

        if (track.stuckTicks >= UnstickConfig.stuckTicks()) {
            rescue(villager, level);
            track.startX = villager.getX();
            track.startZ = villager.getZ();
            track.stuckTicks = 0;
            track.cooldown = UnstickConfig.cooldownTicks();
        }
    }

    /**
     * Teleports the villager a short distance toward its path target (or, if
     * the target is unavailable, the direction it is facing), but only if the
     * landing spot is collision-clear. Never nudges into a wall. Then drops the
     * path so vanilla re-paths from the new position.
     */
    private static void rescue(Villager villager, ServerLevel level) {
        double dirX;
        double dirZ;

        BlockPos target = villager.getNavigation().getTargetPos();
        if (target != null) {
            double tdx = target.getX() + 0.5 - villager.getX();
            double tdz = target.getZ() + 0.5 - villager.getZ();
            double len = Math.sqrt(tdx * tdx + tdz * tdz);
            if (len > 1.0E-4) {
                dirX = tdx / len;
                dirZ = tdz / len;
            } else {
                Vec3 look = villager.getLookAngle();
                dirX = look.x;
                dirZ = look.z;
            }
        } else {
            Vec3 look = villager.getLookAngle();
            dirX = look.x;
            dirZ = look.z;
        }

        double distance = UnstickConfig.rescueDistance();
        for (int attempt = 0; attempt < 2; attempt++) {
            double nx = villager.getX() + dirX * distance;
            double nz = villager.getZ() + dirZ * distance;
            AABB landing = villager.getBoundingBox().move(nx - villager.getX(), 0.0, nz - villager.getZ());
            if (level.noCollision(villager, landing)) {
                villager.teleportTo(level, nx, villager.getY(), nz,
                        Set.<Relative>of(), villager.getYRot(), villager.getXRot(), false);
                villager.getNavigation().stop();
                return;
            }
            // Half the distance and try once more. If even a one-block nudge is
            // blocked, leave the villager where it is rather than shoving it
            // into a wall.
            distance *= 0.5;
        }
    }

    private static final class Track {
        double startX;
        double startZ;
        int stuckTicks;
        int cooldown;

        Track(double startX, double startZ) {
            this.startX = startX;
            this.startZ = startZ;
        }
    }
}
