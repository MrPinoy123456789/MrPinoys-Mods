package chatdonkey.core;

/**
 * The contents of {@code config/chatdonkey/settings.json} (SPEC.md section 7).
 *
 * <p>Every field is a plain value so the fabric side can hand a Gson-parsed
 * instance straight to {@link TriggerRules} without translation. Fields M1 does
 * not enforce yet ({@code maxSimultaneousEventsServerWide}, the grace period
 * pair) are still parsed and round-tripped, so operators who tune them now do
 * not lose their edits when M2 starts reading them.
 */
public record Settings(
        boolean enabled,
        int checkIntervalSeconds,
        double chancePerCheck,
        int cooldownMinutesPerPlayer,
        int minSessionMinutesBeforeFirst,
        int maxSimultaneousEventsServerWide,
        int requireRecentActivitySeconds,
        boolean gracePeriodCommand,
        int gracePeriodMinutes,
        boolean donkeyCanKill,
        double donkeySpeed,
        GiftSettings gifts) {

    /**
     * The master movement dial, set as the donkey's {@code MOVEMENT_SPEED}
     * attribute at spawn.
     *
     * <p>A vanilla donkey is slow -- slower than a sprinting player -- so a
     * donkey trying to obstruct one just trails behind looking sad. This
     * overrides it. Each behavior then applies its own navigation multiplier on
     * top for character (False Alarm sprints, Lecture strolls), so raising this
     * speeds all seven up together without flattening the differences.
     *
     * <p>Roughly horse-tier. Much above this and the pathing starts to overshoot
     * corners, which reads as broken rather than fast.
     */
    public static final double DEFAULT_SPEED = 0.3;

    /**
     * The hard ceiling on how long one event can run, however much chat pays to
     * extend it (SPEC.md section 11).
     *
     * <p>An unbounded {@code extend} is a griefing tool with a price tag: enough
     * redemptions and one player never gets their screen back. Ten minutes is
     * already an eternity for a joke that was designed to last under a minute.
     */
    public static final int MAX_EVENT_SECONDS = 600;

    /**
     * How recently a player must have been hurt to count as "fighting for their
     * life", when {@code donkeyCanKill} is off.
     */
    public static final int COMBAT_RECENCY_SECONDS = 8;

    /** The defaults written on first boot, straight from SPEC.md section 7. */
    public static Settings defaults() {
        return new Settings(true, 120, 0.08, 25, 10, 2, 60, true, 10, true,
                DEFAULT_SPEED, GiftSettings.defaults());
    }

    /** Never null, even if an operator deleted the whole block from the file. */
    public GiftSettings giftsOrDefault() {
        return gifts == null ? GiftSettings.defaults() : gifts;
    }

    /**
     * Clamps anything an operator could plausibly type wrong into a sane range.
     *
     * <p>A cosmetic mod's failure direction is "disable the feature, log it",
     * never "refuse to start" (DESIGN.md section 9.5) -- so a nonsense value is
     * corrected rather than thrown.
     */
    public Settings sanitised() {
        return new Settings(
                enabled,
                Math.max(1, checkIntervalSeconds),
                Math.min(1.0, Math.max(0.0, chancePerCheck)),
                Math.max(0, cooldownMinutesPerPlayer),
                Math.max(0, minSessionMinutesBeforeFirst),
                Math.max(0, maxSimultaneousEventsServerWide),
                Math.max(0, requireRecentActivitySeconds),
                gracePeriodCommand,
                Math.max(0, gracePeriodMinutes),
                donkeyCanKill,
                // Zero would leave a donkey rooted to the spot; the upper bound
                // is where pathing starts overshooting corners.
                Math.min(1.0, Math.max(0.05, donkeySpeed)),
                giftsOrDefault().sanitised());
    }
}
