package chatdonkey.core;

/**
 * The per-player trigger check from SPEC.md section 7: interval, activity gate,
 * cooldown, chance roll.
 *
 * <p>The roll is passed in rather than drawn here so the whole rule set is
 * deterministic under test. Surprise is the point in play (SPEC.md section 7
 * explicitly does <em>not</em> want bounties-style clock-derived determinism),
 * so the caller supplies a plain {@code Random}.
 */
public final class TriggerRules {

    private TriggerRules() {}

    /**
     * @param activeEventCount events running right now, server-wide
     * @param roll             a value in [0, 1); fires when it lands under
     *                         {@code chancePerCheck}
     */
    public static TriggerDecision decide(Settings settings, PlayerTriggerState state,
                                         long now, int activeEventCount, double roll) {
        if (!settings.enabled()) {
            return TriggerDecision.DISABLED;
        }
        // Ahead of the interval check, and non-consuming, so a player who has
        // just opted in is eligible on the very next poll instead of serving
        // out an interval they were never a candidate for.
        if (settings.requiresOptIn() && !state.optedIn()) {
            return TriggerDecision.NOT_OPTED_IN;
        }
        if (now - state.lastCheckedAt() < seconds(settings.checkIntervalSeconds())) {
            return TriggerDecision.NOT_TIME_YET;
        }
        if (state.inEvent()) {
            return TriggerDecision.ALREADY_IN_EVENT;
        }
        if (state.hasGraceAt(now)) {
            return TriggerDecision.IN_GRACE_PERIOD;
        }
        if (now - state.sessionStartedAt() < minutes(settings.minSessionMinutesBeforeFirst())) {
            return TriggerDecision.SESSION_TOO_YOUNG;
        }
        if (now - state.lastMovedAt() > seconds(settings.requireRecentActivitySeconds())) {
            return TriggerDecision.INACTIVE;
        }
        if (state.lastEventEndedAt() != PlayerTriggerState.NEVER
                && now - state.lastEventEndedAt() < minutes(settings.cooldownMinutesPerPlayer())) {
            return TriggerDecision.ON_COOLDOWN;
        }
        // Checked before the roll: a full server should not burn its luck.
        if (activeEventCount >= settings.maxSimultaneousEventsServerWide()) {
            return TriggerDecision.SERVER_BUSY;
        }
        return roll < settings.chancePerCheck()
                ? TriggerDecision.TRIGGER
                : TriggerDecision.ROLL_FAILED;
    }

    private static long seconds(int s) {
        return s * 1000L;
    }

    private static long minutes(int m) {
        return m * 60_000L;
    }
}
