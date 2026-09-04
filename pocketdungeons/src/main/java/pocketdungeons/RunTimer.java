package pocketdungeons;

import net.minecraft.server.level.ServerPlayer;

/**
 * The elapsed-time tracker for a keystone run (M48, spec 5.3).
 *
 * <p>The boss bar is gone. The timer still records total run time for the
 * completion line, the diary, and future leaderboard use. It stops being a
 * gate: omen (see {@link Omen}) determines the keystone consequence on
 * completion, not the clock. The timeout still fires independently through
 * {@code overTime}, depleting the keystone via {@code RunLifecycle.expireTimedOut}.
 *
 * <p>{@code addPlayer}, {@code removePlayer} and {@code close} are retained as
 * no-ops so {@code Instances}, which this milestone does not own, still compiles.
 * A later wave that takes {@code Instances} back can remove the calls.
 */
final class RunTimer {

    private final int totalSeconds;
    private final int roomCount;
    private final String prefix;

    private int elapsedTicks;
    private int roomsSeen;
    private boolean overTime;
    private boolean completed;

    RunTimer(int keystoneLevel, int totalSeconds, int roomCount) {
        this("Keystone [" + keystoneLevel + "]", totalSeconds, roomCount);
    }

    /** M25: a countdown with a custom label, for a Pocket2 child's clock. */
    RunTimer(String prefix, int totalSeconds, int roomCount) {
        this.prefix = prefix;
        this.totalSeconds = Math.max(1, totalSeconds);
        this.roomCount = Math.max(1, roomCount);
    }

    // No-ops retained for Instances.java; the boss bar is gone (M48).
    void addPlayer(ServerPlayer player) {}

    void removePlayer(ServerPlayer player) {}

    void close() {}

    /** How many distinct rooms the party has stood in. Kept for future use. */
    void notePresence(int rooms) {
        if (rooms > roomsSeen) {
            roomsSeen = rooms;
        }
    }

    boolean overTime() {
        return overTime;
    }

    int secondsRemaining() {
        return Math.max(0, totalSeconds - elapsedTicks / 20);
    }

    /** The clock's full length, for scoring a completion against how much of it is left. */
    int totalSeconds() {
        return totalSeconds;
    }

    /** Elapsed seconds since the run started, for the completion line and diary. */
    int elapsedSeconds() {
        return elapsedTicks / 20;
    }

    /**
     * Advances the clock by {@code ticks}.
     *
     * <p>Called from the existing instance watcher rather than every tick, so
     * {@code ticks} is the watcher's interval.
     */
    void tick(int ticks) {
        if (completed) {
            return;
        }
        elapsedTicks += Math.max(0, ticks);
        if (!overTime && secondsRemaining() <= 0) {
            overTime = true;
        }
    }

    /** Freezes the clock at its last reading once the run has been completed. */
    void markCompleted() {
        completed = true;
    }
}
