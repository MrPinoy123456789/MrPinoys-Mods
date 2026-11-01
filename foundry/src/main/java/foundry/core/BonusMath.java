package foundry.core;

/**
 * The drop arithmetic of a {@link BonusDef}, with the dice passed in so it is testable. A bonus never lowers what the
 * player already has: it only tops the tool's own level up, to the bonus's cap.
 */
public final class BonusMath {
    private BonusMath() {}

    /** The levels the bonus really adds: the tool's level plus {@code bonus}, held at {@code cap}, minus the tool's level. */
    public static int gain(int toolLevel, int bonus, int cap) {
        int tool = Math.max(0, toolLevel);
        int total = Math.min(Math.max(cap, tool), tool + Math.max(0, bonus));
        return total - tool;
    }

    /**
     * Extra copies of a drop for a Fortune style bonus, in the vanilla ore shape: with chance {@code gain / (gain + 2)}
     * the drop doubles, so Fortune I adds a third of a drop on average. {@code roll} is in [0, 1).
     */
    public static int fortuneExtra(int gain, int baseCount, double roll) {
        if (gain <= 0 || baseCount <= 0) return 0;
        return roll < (double) gain / (gain + 2) ? baseCount : 0;
    }

    /** Extra items for a Looting style bonus: a whole number from 0 to {@code gain}, evenly. {@code roll} is in [0, 1). */
    public static int lootingExtra(int gain, double roll) {
        if (gain <= 0) return 0;
        return Math.min(gain, (int) Math.floor(roll * (gain + 1)));
    }

    /** The extra count for a kind. */
    public static int extra(BonusDef.Kind kind, int gain, int baseCount, double roll) {
        return kind == BonusDef.Kind.FORTUNE ? fortuneExtra(gain, baseCount, roll) : lootingExtra(gain, roll);
    }
}
