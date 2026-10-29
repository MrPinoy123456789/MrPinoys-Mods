package foundry.core.mechanics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs a mechanic with the safety rules: cooldown, probability, a per-actor rate limit and a re-entrancy guard so
 * an action can never set off another mechanic in the same call. Pure; the world is reached only through {@link Host}.
 * Not thread safe: call on the server thread.
 */
public final class MechanicRunner {
    /** Most firings one actor may start inside {@link #WINDOW_TICKS}. */
    public static final int MAX_FIRES_PER_WINDOW = 20;
    public static final long WINDOW_TICKS = 20;

    public interface Host {
        long nowTick();
        boolean hasPermission(int level);
        boolean roll(double probability);
        /** Performs one validated action. Must not fire mechanics. */
        void perform(Step action);
    }

    public enum Outcome { RAN, ON_COOLDOWN, CONDITION_FAILED, CHANCE_FAILED, RATE_LIMITED, REENTERED }

    private final Map<String, Long> readyAt = new HashMap<>();
    private final Map<String, Deque<Long>> recent = new HashMap<>();
    private boolean running;

    public Outcome fire(String actor, String contentId, int mechanicIndex, MechanicDef m, Host host) {
        if (running) return Outcome.REENTERED;
        long now = host.nowTick();
        Deque<Long> window = recent.computeIfAbsent(actor, k -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_TICKS) window.pollFirst();
        if (window.size() >= MAX_FIRES_PER_WINDOW) return Outcome.RATE_LIMITED;

        String key = actor + "|" + contentId + "|" + mechanicIndex;
        if (m.cooldownTicks() > 0 && readyAt.getOrDefault(key, 0L) > now) return Outcome.ON_COOLDOWN;
        for (Step c : m.conditions()) {
            if (c.type().equals("has_permission") && !host.hasPermission(c.intParam("level", 4))) {
                return Outcome.CONDITION_FAILED;
            }
        }
        if (m.probability() < 1.0 && !host.roll(m.probability())) return Outcome.CHANCE_FAILED;

        window.addLast(now);
        if (m.cooldownTicks() > 0) readyAt.put(key, now + m.cooldownTicks());
        running = true;
        try {
            int count = 0;
            for (Step a : m.actions()) {
                if (++count > MechanicDef.MAX_ACTIONS) break;
                host.perform(a);
            }
        } finally {
            running = false;
        }
        return Outcome.RAN;
    }
}
