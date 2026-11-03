package foundry.core;

/**
 * The numbers of a part-charged dash strike (the Oathblade's Quickdraw), kept pure so they can be tested: a charge from
 * 0 (the shortest hold that fires) to 1 (full) moves the reach from {@code minRange} to {@code range}, and the damage
 * from {@code minDamage} times the amount up to the full amount.
 */
public final class Iai {
    private Iai() {}

    public static double reach(double charge, double minRange, double range) {
        double c = clamp(charge);
        return Math.min(minRange, range) + (range - Math.min(minRange, range)) * c;
    }

    public static double damage(double charge, double amount, double minDamage) {
        double c = clamp(charge);
        double floor = Math.max(0, Math.min(1, minDamage));
        return amount * (floor + (1 - floor) * c);
    }

    /** A charge meter of {@code cells} cells, filled to {@code charge}: "▮▮▮▯▯". */
    public static String meter(double charge, int cells) {
        int full = (int) Math.round(clamp(charge) * cells);
        return "▮".repeat(full) + "▯".repeat(cells - full);
    }

    private static double clamp(double c) {
        return Math.max(0, Math.min(1, c));
    }
}
