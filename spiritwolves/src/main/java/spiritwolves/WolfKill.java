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
            // Wild wolves hunt too -- keep them off the inventory walk entirely.
            if (!(damageSource.getEntity() instanceof Wolf wolf) || !wolf.isTame()) {
                return;
            }
            ServerPlayer owner = Tracker.findOwner(level, wolf.getUUID());
            if (owner == null) {
                return;
            }
            WolfRecord record = PlayerWolfRegistry.get(owner.getUUID());
            if (record == null) {
                return;
            }

            Streak.onKill(wolf, owner);
            Fetch.recordKill(level, owner, entity.position());
            Souls.onKill(wolf, owner, record, entity);
            Verbs.onKill(wolf, owner, record, entity);
            VerbProcs.onKill(wolf, owner, record, entity);
        });
    }
}
