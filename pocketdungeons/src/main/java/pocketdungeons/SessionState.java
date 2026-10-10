package pocketdungeons;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One place that knows which static collections hold per player or per slot state, so a disconnect and a
 * server stop clear them all (code audit 2026-10-10, item 8). A class declares such a collection through
 * one of the wrappers below, which registers it here as it is created and hands the same collection back:
 *
 * <pre>{@code private static final Map<UUID, Long> lastHint = SessionState.perPlayer(new HashMap<>());}</pre>
 *
 * <ul>
 *   <li>{@link #perPlayer}: dropped for one player on their disconnect, and emptied at server stop.</li>
 *   <li>{@link #clearedAtStop}: emptied at server stop only (cell, slot or run state that has its own
 *       earlier teardown; the stop clear matters when one JVM loads a second world).</li>
 * </ul>
 *
 * <p>Registration happens when the owning class initialises, which is also the first moment it can hold
 * anything. {@code Instances.handleDisconnect} (already on the server thread, PD-198) walks
 * {@link #forgetPlayer}, and its {@code SERVER_STOPPING} hook walks {@link #clearAll}.
 * {@code SessionStateTest} fails when a static map or list in the main sources is neither registered here
 * nor named, with its reason, in that test's allowlist.
 */
public final class SessionState {

    private static final List<Consumer<UUID>> PLAYER_HOOKS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> STOP_HOOKS = new CopyOnWriteArrayList<>();

    private SessionState() {}

    /** Registers a player keyed map: an entry is dropped on that player's disconnect, all at server stop. */
    public static <V> Map<UUID, V> perPlayer(Map<UUID, V> map) {
        PLAYER_HOOKS.add(map::remove);
        STOP_HOOKS.add(map::clear);
        return map;
    }

    /** Registers a map that is emptied at server stop. */
    public static <K, V> Map<K, V> clearedAtStop(Map<K, V> map) {
        STOP_HOOKS.add(map::clear);
        return map;
    }

    /** Registers a list that is emptied at server stop. */
    public static <E> List<E> clearedAtStop(List<E> list) {
        STOP_HOOKS.add(list::clear);
        return list;
    }

    /** Drops everything registered per player for one player. Server thread only. */
    static void forgetPlayer(UUID player) {
        for (Consumer<UUID> hook : PLAYER_HOOKS) {
            hook.accept(player);
        }
    }

    /** Empties everything registered. Called at server stop. */
    static void clearAll() {
        for (Runnable hook : STOP_HOOKS) {
            hook.run();
        }
    }
}
