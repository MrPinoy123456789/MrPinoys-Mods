package spiritwolves;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;

/**
 * The single kill-attribution hook: something died to a summoned spirit wolf.
 *
 * <p>{@link Streak} and {@link Fetch} both need this, and resolving the owner
 * means walking every online player's inventory copying {@code custom_data} as
 * it goes. Doing that once and dispatching is worth the extra class.
 */
public final class WolfKill {

    private WolfKill() {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity.level() instanceof ServerLevel level)) {
                return;
            }
            Assists.forget(entity.getUUID());

            if (damageSource.getEntity() instanceof Wolf wolf && wolf.isTame()) {
                ServerPlayer owner = Tracker.findOwner(level, wolf.getUUID());
                if (owner != null) {
                    WolfRecord record = PlayerWolfRegistry.get(owner.getUUID());
                    if (record != null) {
                        Streak.onKill(wolf, owner);
                        Fetch.recordKill(level, owner, entity.position());
                        Souls.onKill(wolf, owner, record, entity);
                        Verbs.onKill(wolf, owner, record, entity);
                        VerbProcs.onKill(wolf, owner, record, entity);
                    }
                }
                return;
            }

            // Not a direct wolf kill -- check whether a summoned spirit wolf
            // damaged the target recently.
            Assists.claim(level, entity);
        });
    }
}
