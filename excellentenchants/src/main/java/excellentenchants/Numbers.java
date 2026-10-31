package excellentenchants;

/**
 * Every number the five-plus enchantments use, from the Excellent Enchants plugin's
 * defaults. The plugin evaluates a modifier as base + perLevel * level, then caps it.
 * Kept pure so the tests can pin each one without a world.
 */
public final class Numbers {

    private Numbers() {
    }

    /** The plugin's modifier: base + perLevel * level, capped when the cap is above zero. */
    public static double modifier(double base, double perLevel, double cap, int level) {
        double value = perLevel == 0 ? base : base + perLevel * level;
        return cap > 0 ? Math.min(value, cap) : value;
    }

    /** Trigger chance in percent, capped at 100. */
    public static double chance(double base, double perLevel, int level) {
        return modifier(base, perLevel, 100, level);
    }

    /** The potion amplifier the plugin applies is its level value minus one, never below zero. */
    public static int amplifier(int level) {
        return Math.max(0, (int) modifier(0, 1, 5, level) - 1);
    }

    public static double thunderChance(int level) {
        return chance(5, 2, level);
    }

    public static double thunderDamage(int level) {
        return modifier(1.25, 0.25, 1000, level);
    }

    public static int flameWalkerRadius(int level) {
        return (int) modifier(1, 1, 16, level);
    }

    /** Longest life in seconds of a magma block before it turns back to lava. */
    public static int flameWalkerDecaySeconds(int level) {
        return (int) modifier(8, 1, 15, level);
    }

    public static double iceAspectChance(int level) {
        return 100;
    }

    /** Slowness seconds. */
    public static int iceAspectSeconds(int level) {
        return (int) modifier(3, 1, 10, level);
    }

    public static double venomChance(int level) {
        return chance(5, 5, level);
    }

    public static int venomSeconds(int level) {
        return (int) modifier(2, 1, 10, level);
    }

    public static double witherChance(int level) {
        return chance(4, 3, level);
    }

    public static int witherSeconds(int level) {
        return (int) modifier(4, 2, 10, level);
    }

    public static double wisdomMultiplier(int level) {
        return modifier(1, 0.5, 3, level);
    }

    /** The orb total after Wisdom: the plugin rounds the multiplied amount up. */
    public static int wisdomXp(int original, int level) {
        return (int) Math.ceil(original * wisdomMultiplier(level));
    }

    public static double decapitatorChance(int level) {
        return chance(1, 2, level);
    }

    public static double thriftyChance(int level) {
        return chance(0, 1, level);
    }

    public static double silkSpawnerChance(int level) {
        return chance(0, 25, level);
    }

    public static double ghastYield(int level) {
        return modifier(2, 0, 5, level);
    }

    /** Blocks a Tunnel swing breaks, the centre block included. */
    public static int tunnelBlocks(int level) {
        return level <= 1 ? 2 : level == 2 ? 5 : 9;
    }
}
