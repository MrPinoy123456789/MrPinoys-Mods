package hearsay.core;

/**
 * Pure edge-detection helpers for world-state changes.
 *
 * <p>Fabric-side code calls these with snapshots taken once per tick;
 * the math here is what is under test without a server running.
 */
public final class Triggers {
    private Triggers() {}

    /** Minecraft day time wraps at 24000; dawn is the wrap from night into morning. */
    public static boolean crossedDawn(long prevTimeOfDay, long nowTimeOfDay) {
        return prevTimeOfDay > nowTimeOfDay && nowTimeOfDay < 1000;
    }

    /** Dusk is the crossing into the 12000 tick boundary. */
    public static boolean crossedDusk(long prevTimeOfDay, long nowTimeOfDay) {
        return prevTimeOfDay < nowTimeOfDay && prevTimeOfDay < 12000 && nowTimeOfDay >= 12000;
    }

    public static boolean weatherStarted(boolean wasRaining, boolean isRaining) {
        return isRaining && !wasRaining;
    }
}
