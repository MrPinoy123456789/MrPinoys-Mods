package charter.time;

/** Millisecond helpers. Every duration in the core model is a long of milliseconds. */
public final class Durations {

    public static final long SECOND = 1_000L;
    public static final long MINUTE = 60 * SECOND;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;

    private Durations() {
    }

    public static long seconds(double n) { return Math.round(n * SECOND); }
    public static long minutes(double n) { return Math.round(n * MINUTE); }
    public static long hours(double n) { return Math.round(n * HOUR); }
    public static long days(double n) { return Math.round(n * DAY); }
}
