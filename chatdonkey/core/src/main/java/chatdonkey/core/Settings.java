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
        GiftSettings gifts) {

    /** The defaults written on first boot, straight from SPEC.md section 7. */
    public static Settings defaults() {
        return new Settings(true, 120, 0.08, 25, 10, 2, 60, true, 10,
                GiftSettings.defaults());
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
                giftsOrDefault().sanitised());
    }
}
