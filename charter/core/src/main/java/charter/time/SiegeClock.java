package charter.time;

/**
 * The siege clock (PLAN.md §4.1): a saved counter of siege milliseconds. Each
 * sim step adds real elapsed time multiplied by the time scale. It does not
 * advance while the server is down, because nothing calls it then (D14).
 *
 * <p>Governance (votes, membership age, the absent Lord rule) runs on wall
 * time and never reads this clock.
 */
public final class SiegeClock {

    private long now;

    public long now() {
        return now;
    }

    /** Advances by real elapsed milliseconds at the given time scale; returns the siege delta. */
    public long advance(long realElapsedMillis, double timeScale) {
        if (realElapsedMillis <= 0) return 0;
        long delta = Math.round(realElapsedMillis * timeScale);
        now += delta;
        return delta;
    }

    /** Adds siege time directly (admin "time advance"). */
    public void advanceSiege(long siegeMillis) {
        if (siegeMillis > 0) now += siegeMillis;
    }

    /** Restores the counter after loading. */
    public void restore(long value) {
        now = value;
    }
}
