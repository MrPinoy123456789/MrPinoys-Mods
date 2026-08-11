package spiritwolves;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A short window during which a summoned wolf cannot be voluntarily recalled --
 * five seconds after being summoned, and five seconds after taking any damage.
 *
 * <p>This is what keeps {@link Streak} honest. Without it, the correct play on
 * spotting trouble is to right-click the stone and bench the wolf, which turns
 * a killstreak into a reason to stop using the wolf. With it, committing the
 * wolf to a fight is a real commitment.
 *
 * <p>Only the voluntary right-click recall is blocked. The ownership invariant
 * in {@link Tracker} -- stone leaves the inventory, owner logs out -- always
 * wins, because an orphaned wolf has no stone to decrement on death.
 */
public final class RecallLock {

    private static final int LOCK_TICKS = 100;

    /** Wolf UUID -> server tick at which recall becomes available again. */
    private static final Map<UUID, Integer> lockedUntil = new HashMap<>();

    private RecallLock() {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, blocked, taken, blockedByShield) -> {
            if (taken <= 0.0f || !(entity instanceof Wolf wolf)) {
                return;
            }
            if (!(entity.level() instanceof ServerLevel level)) {
                return;
            }
            // Only bother for wolves that are actually bound to someone's stone.
            if (!wolf.isTame() || Tracker.findOwner(level, wolf.getUUID()) == null) {
                return;
            }
            lock(wolf, level);
        });
    }

    /** Starts the lock window for this wolf. */
    public static void lock(Wolf wolf, ServerLevel level) {
        lockedUntil.put(wolf.getUUID(), level.getServer().getTickCount() + LOCK_TICKS);
    }

    /** Whole seconds left on the lock, or 0 if recall is available. */
    public static int secondsRemaining(Wolf wolf, ServerLevel level) {
        Integer until = lockedUntil.get(wolf.getUUID());
        if (until == null) {
            return 0;
        }
        int ticksLeft = until - level.getServer().getTickCount();
        if (ticksLeft <= 0) {
            lockedUntil.remove(wolf.getUUID());
            return 0;
        }
        return Math.max(1, (int) Math.ceil(ticksLeft / 20.0));
    }

    /** Clears the lock for a wolf that is no longer out. */
    public static void forget(UUID wolfUuid) {
        lockedUntil.remove(wolfUuid);
    }
}
