package pocketdungeons;

/**
 * One shared chance roll, kept apart so a test can pin it and a caller never
 * has to spell the bounds checks twice. Was {@code Fuel.rollChance} before J1
 * retired the fuel currency; the rule itself has nothing to do with currency.
 */
final class Rolls {

    private Rolls() {}

    /** Whether a per-event {@code chance} (0.0 to 1.0) rolls true for {@code random}. */
    static boolean chance(net.minecraft.util.RandomSource random, double chance) {
        return chance >= 1.0 || (chance > 0.0 && random.nextDouble() < chance);
    }
}
