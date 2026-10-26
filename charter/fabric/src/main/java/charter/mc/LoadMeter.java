package charter.mc;

/**
 * How long Charter's own tick work takes on the server thread: the sim step and the
 * crossing checks, summed since the last {@link #reset}. The load gametest reads it
 * (PLAN.md risk R1); it costs two clock reads a tick.
 */
public final class LoadMeter {

    private static long stepNanos;
    private static long steps;
    private static long crossingNanos;
    private static long crossingTicks;

    private LoadMeter() {
    }

    public static void reset() {
        stepNanos = steps = crossingNanos = crossingTicks = 0;
    }

    static void step(long nanos) {
        stepNanos += nanos;
        steps++;
    }

    public static void crossings(long nanos) {
        crossingNanos += nanos;
        crossingTicks++;
    }

    /** Mean milliseconds per sim step since the reset. */
    public static double stepMillis() {
        return steps == 0 ? 0 : stepNanos / 1e6 / steps;
    }

    /** Mean milliseconds per tick of crossing checks since the reset. */
    public static double crossingMillis() {
        return crossingTicks == 0 ? 0 : crossingNanos / 1e6 / crossingTicks;
    }

    public static long steps() {
        return steps;
    }
}
