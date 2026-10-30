package pocketdungeons;

import java.util.function.LongSupplier;

/**
 * Wall clock for wild mode, in epoch seconds. Cooldowns and lifespans use real time so a stopped server does not freeze
 * them. Tests replace the source with {@link #setSourceForTesting(LongSupplier)}.
 */
final class WildClock {

    private static LongSupplier source = () -> System.currentTimeMillis() / 1000L;

    private WildClock() {}

    static long now() {
        return source.getAsLong();
    }

    /** Test seam: {@code null} restores the real clock. */
    static void setSourceForTesting(LongSupplier replacement) {
        source = replacement != null ? replacement : () -> System.currentTimeMillis() / 1000L;
    }
}
