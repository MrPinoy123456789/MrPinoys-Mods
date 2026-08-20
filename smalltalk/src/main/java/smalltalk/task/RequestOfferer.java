package smalltalk.task;

import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import smalltalk.SmallTalkConfig;

/**
 * SPEC.md section 12.3: the daily cadence gate, the actual retention
 * mechanic. {@link #maybeOffer} is called from {@code interaction.TalkHandler}
 * before the ambient-context greeting is computed -- never from a tick loop,
 * same "nothing evaluates until the player initiates" discipline as the rest
 * of this mod (SPEC.md section 0).
 *
 * <p>Also owns the SPEC.md section 12.2 pending-request particle tell,
 * registered via {@link #register()} -- it piggybacks on Fabric's
 * entity-tracking event (fired when a player's client starts seeing an
 * entity, the same visibility check vanilla already runs), rather than a
 * tick loop of its own.
 */
public final class RequestOfferer {

    /** Generous, in-game days (SPEC.md section 12.1: "expiry in in-game days, generous"). */
    private static final long TICKS_PER_DAY = 24000L;
    private static final long EXPIRES_AFTER_TICKS = TICKS_PER_DAY * 3;

    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 4;

    private RequestOfferer() {}

    public static void register() {
        EntityTrackingEvents.START_TRACKING.register(RequestOfferer::onStartTracking);
    }

    /**
     * Rolls a new Fetch offer for this resident-player pair if, and only if,
     * every SPEC.md section 12.3 gate passes. A no-op (not an error) if any
     * gate fails -- most talks won't roll anything, which is the point.
     */
    public static void maybeOffer(Villager villager, ServerPlayer player, long currentTick) {
        if (!SmallTalkConfig.requestsEnabled() || villager.isBaby()) {
            return;
        }
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }

        TaskRegistry registry = TaskRegistry.of(level);

        if (registry.activeTask(villager.getUUID(), player.getUUID(), currentTick).isPresent()) {
            return;
        }
        if (registry.activeCountFor(player.getUUID(), currentTick) >= SmallTalkConfig.maxActiveTasks()) {
            return;
        }
        long lastOffered = registry.lastOfferedTick(villager.getUUID());
        if (lastOffered != TaskRegistry.NEVER
                && currentTick - lastOffered < SmallTalkConfig.requestCooldownTicks()) {
            return;
        }

        Identifier item = FetchTaskLogic.randomFetchableItem(level.getRandom());
        int count = MIN_COUNT + level.getRandom().nextInt(MAX_COUNT - MIN_COUNT + 1);
        Task task = FetchTaskLogic.offer(villager.getUUID(), player.getUUID(), item, count,
                currentTick, currentTick + EXPIRES_AFTER_TICKS);

        registry.add(task);
        registry.recordOffer(villager.getUUID(), currentTick);
    }

    /**
     * SPEC.md section 12.2's pending-request tell: presence-only, no text,
     * no sound, and never fires for a resident with no OFFERED task -- this
     * check re-reads the registry every time, so it naturally stops once the
     * task leaves OFFERED, with nothing to unregister or sweep.
     */
    private static void onStartTracking(Entity trackedEntity, ServerPlayer player) {
        if (!(trackedEntity instanceof Villager villager) || villager.isBaby()) {
            return;
        }
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }

        boolean hasOfferedTask = TaskRegistry.of(level).tasks().stream()
                .anyMatch(t -> t.issuer().equals(villager.getUUID())
                        && t.assignee().equals(player.getUUID())
                        && t.state() == TaskState.OFFERED);
        if (!hasOfferedTask) {
            return;
        }

        level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, false, false,
                villager.getX(), villager.getEyeY(), villager.getZ(), 1, 0.3, 0.3, 0.3, 0.0);
    }
}
