package rehome;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Wolf rules, deliberately (SPEC.md section 5): close, idle; mid-range, walk;
 * far or a dimension away, teleport in. No registry -- every tick this simply
 * scans loaded villagers for the follower attachment, which is cheap at the
 * scale this mod operates at.
 */
public final class FollowBehavior {

    private static final int TICK_INTERVAL = 10;
    private static final double NAV_SPEED = 0.6;

    private static int tickCounter;

    private FollowBehavior() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % TICK_INTERVAL != 0) {
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                List<? extends Villager> followers = level.getEntities(
                        EntityTypeTest.<net.minecraft.world.entity.Entity, Villager>forClass(Villager.class),
                        FollowerAttachment::isFollowing);
                for (Villager villager : followers) {
                    tick(villager, server);
                }
            }
        });
    }

    private static void tick(Villager villager, MinecraftServer server) {
        if (FollowerAttachment.isWaiting(villager)) {
            return;
        }

        // Vanilla's own brain keeps running underneath the follow behaviour and
        // will happily claim any bed the villager wanders near -- a village
        // passed through mid-journey, say -- with no idea it's supposed to be
        // homeless while following. Left alone, that claim sticks silently, and
        // when the player later says "stay" the villager walks all the way back
        // to whatever it grabbed instead of finding something near wherever it
        // actually is. Reject it every tick so nothing sticks until "stay".
        if (villager.getBrain().hasMemoryValue(MemoryModuleType.HOME)) {
            villager.releasePoi(MemoryModuleType.HOME);
            villager.getBrain().eraseMemory(MemoryModuleType.HOME);
        }

        UUID ownerUuid = FollowerAttachment.followerOf(villager);
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null) {
            // Logged off. Stand where you are; resumes on login if the chunk stays loaded.
            return;
        }

        if (owner.level() != villager.level()) {
            teleportNear(villager, owner);
            return;
        }

        double distanceSqr = villager.distanceToSqr(owner);
        double teleportDistance = RehomeConfig.followTeleportDistance();
        double startDistance = RehomeConfig.followStartDistance();

        if (distanceSqr > teleportDistance * teleportDistance) {
            teleportNear(villager, owner);
        } else if (distanceSqr > startDistance * startDistance) {
            villager.getNavigation().moveTo(owner, NAV_SPEED);
        }
        // Within followStartDistance: idle, let vanilla wander/look-around take over.
    }

    private static void teleportNear(Villager villager, ServerPlayer owner) {
        if (!(owner.level() instanceof ServerLevel ownerLevel)) {
            return;
        }
        double dx = (villager.getRandom().nextDouble() - 0.5) * 4.0;
        double dz = (villager.getRandom().nextDouble() - 0.5) * 4.0;
        villager.teleportTo(ownerLevel, owner.getX() + dx, owner.getY(), owner.getZ() + dz,
                Set.<Relative>of(), villager.getYRot(), villager.getXRot(), false);
    }
}
