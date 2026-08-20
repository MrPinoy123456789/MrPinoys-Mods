package kamutotems;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import kamutotems.core.Effect;

/**
 * Holds delayed effects for the {@code echo} delivery and pays them out when
 * the configured number of ticks has passed.
 *
 * <p>Nothing in this class keeps a hard reference to an {@link Entity} across
 * ticks. It stores UUIDs and re-resolves them at payoff time. If the source
 * (caster) is gone, the whole entry is dropped. If only the target is gone,
 * self-benefiting effects ({@code heal}, {@code absorption}) still pay out to
 * the source, because the swing landed.
 */
public final class EchoQueue {

    private static final List<Pending> pending = new ArrayList<>();

    private EchoQueue() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(EchoQueue::tick);
    }

    /**
     * Schedule a list of effects to land later.
     *
     * @param level   the level the swing happened in
     * @param source  the caster
     * @param target  the struck entity (may be null for self-only effects)
     * @param effects resolved effect list
     * @param delay   ticks to wait
     */
    public static void queue(ServerLevel level, LivingEntity source, Entity target,
                             List<Effect> effects, int delay) {
        if (level == null || source == null || effects == null || effects.isEmpty()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        // ⚠ UNVERIFIED: MinecraftServer.getTickCount() in 26.2
        int due = server.getTickCount() + delay;
        pending.add(new Pending(
                source.getUUID(),
                target != null ? target.getUUID() : null,
                level.dimension(),
                due,
                List.copyOf(effects)));
    }

    /** Pays out due echo deliveries after resolving their source and target UUIDs. */
    private static void tick(MinecraftServer server) {
        // ⚠ UNVERIFIED: MinecraftServer.getTickCount() in 26.2
        int now = server.getTickCount();
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.dueTick > now) {
                continue;
            }
            it.remove();

            ServerLevel originLevel = server.getLevel(p.dimension);
            if (originLevel == null) {
                continue;
            }
            LivingEntity source = resolveLiving(originLevel, p.sourceUuid);
            if (source == null) {
                // The caster logged out, died, or left the originating dimension.
                continue;
            }

            Entity target = p.targetUuid != null ? originLevel.getEntity(p.targetUuid) : null;
            EffectsMc.apply(p.effects, originLevel, source, target);
        }
    }

    /** Finds a living source in the dimension where the echo was queued. */
    private static LivingEntity resolveLiving(ServerLevel level, UUID id) {
        Entity entity = level.getEntity(id);
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    private record Pending(UUID sourceUuid, UUID targetUuid,
                           ResourceKey<Level> dimension, int dueTick,
                           List<Effect> effects) {}
}
