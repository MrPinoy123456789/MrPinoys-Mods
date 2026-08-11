package chatdonkey;

import chatdonkey.core.PlayerTriggerState;
import chatdonkey.core.Settings;
import chatdonkey.core.TriggerDecision;
import chatdonkey.core.TriggerRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Per-player timers and the activity gate (SPEC.md section 7).
 *
 * <p>All in memory, all forgotten on restart -- a deliberate and harmless
 * failure direction (SPEC.md section 2).
 */
public final class Triggers {

    /** How far a player must move to count as active, in blocks squared. */
    private static final double MOVED_THRESHOLD_SQR = 2.0 * 2.0;

    /** The trigger check is per-player and interval-gated; this is just the polling rate. */
    private static final int POLL_INTERVAL_TICKS = 20;

    private final Map<UUID, PlayerTriggerState> states = new HashMap<>();
    private final Map<UUID, Vec3> lastPositions = new HashMap<>();
    private final Random random = new Random();

    private int tickCounter;

    public PlayerTriggerState state(ServerPlayer player) {
        return states.computeIfAbsent(player.getUUID(),
                id -> new PlayerTriggerState(System.currentTimeMillis()));
    }

    public void forget(UUID playerId) {
        states.remove(playerId);
        lastPositions.remove(playerId);
    }

    /** Clears the in-event flag without levying -- or clearing -- a cooldown. */
    public void clearEventFlag(UUID playerId) {
        PlayerTriggerState state = states.get(playerId);
        if (state != null) {
            state.markEventAborted();
        }
    }

    /**
     * Updates activity and runs the trigger check for every online player.
     *
     * @param events the runner, asked to start an event on a hit
     */
    public void tick(Iterable<ServerPlayer> players, Settings settings, Events events) {
        if (++tickCounter < POLL_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        long now = System.currentTimeMillis();

        for (ServerPlayer player : players) {
            // A spectator cannot be obstructed, cannot be handed a gift, and
            // cannot see the joke land. Nothing about the event works on them.
            if (player.isSpectator()) {
                continue;
            }

            UUID id = player.getUUID();
            PlayerTriggerState state = state(player);

            Vec3 position = player.position();
            Vec3 previous = lastPositions.get(id);
            if (previous == null || previous.distanceToSqr(position) >= MOVED_THRESHOLD_SQR) {
                state.markMoved(now);
                lastPositions.put(id, position);
            }

            TriggerDecision decision = TriggerRules.decide(
                    settings, state, now, events.activeCount(), random.nextDouble());
            if (decision.consumedCheck()) {
                state.markChecked(now);
            }
            if (decision.fires()) {
                // A failed spawn search re-rolls on the next interval rather
                // than burning the player's cooldown (SPEC.md section 6).
                events.start(player);
            }
        }
    }

    /** Grants immunity for {@code minutes} (SPEC.md section 7's escape hatch). */
    public void grantGrace(ServerPlayer player, int minutes) {
        state(player).grantGraceUntil(System.currentTimeMillis() + minutes * 60_000L);
    }

    /**
     * Cancels immunity -- chat outbidding the streamer (SPEC.md section 7).
     *
     * @return false if the player had no active grace to revoke
     */
    public boolean revokeGrace(ServerPlayer player) {
        PlayerTriggerState state = state(player);
        if (!state.hasGraceAt(System.currentTimeMillis())) {
            return false;
        }
        state.revokeGrace();
        return true;
    }
}
