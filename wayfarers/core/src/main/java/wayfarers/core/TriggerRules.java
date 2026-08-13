package wayfarers.core;

/**
 * Per-player trigger gates (SPEC.md §8).
 *
 * <p>Decisions are return values, not exceptions. A plain {@code Random} roll
 * is passed in so the whole gate is deterministic under test.
 */
public final class TriggerRules {

    private TriggerRules() {}

    public record Settings(
            boolean enabled,
            int checkIntervalSeconds,
            int minSessionMinutes,
            int cooldownMinutes,
            int recentActivitySeconds,
            int maxSimultaneous,
            int proximityBlocks,
            double chance) {}

    public record PlayerState(
            long sessionStartedAt,
            long lastCheckedAt,
            long lastMovedAt,
            long lastEventEndedAt,
            boolean inEvent,
            long graceUntil,
            boolean nearEncounter) {}

    public enum Decision {
        DISABLED,
        NOT_TIME_YET,
        ALREADY_IN_EVENT,
        IN_GRACE,
        SESSION_TOO_YOUNG,
        INACTIVE,
        ON_COOLDOWN,
        SERVER_BUSY,
        PROXIMITY,
        ROLL_FAILED,
        TRIGGER
    }

    public static Decision decide(Settings s, PlayerState p, long now, int activeCount, double roll) {
        if (!s.enabled) return Decision.DISABLED;
        if (now - p.lastCheckedAt < seconds(s.checkIntervalSeconds)) return Decision.NOT_TIME_YET;
        if (p.inEvent) return Decision.ALREADY_IN_EVENT;
        if (p.graceUntil > now) return Decision.IN_GRACE;
        if (now - p.sessionStartedAt < minutes(s.minSessionMinutes)) return Decision.SESSION_TOO_YOUNG;
        if (now - p.lastMovedAt > seconds(s.recentActivitySeconds)) return Decision.INACTIVE;
        if (p.lastEventEndedAt != 0 && now - p.lastEventEndedAt < minutes(s.cooldownMinutes)) return Decision.ON_COOLDOWN;
        if (p.nearEncounter) return Decision.PROXIMITY;
        if (activeCount >= s.maxSimultaneous) return Decision.SERVER_BUSY;
        return roll < s.chance ? Decision.TRIGGER : Decision.ROLL_FAILED;
    }

    private static long seconds(int s) { return s * 1000L; }
    private static long minutes(int m) { return m * 60_000L; }
}
