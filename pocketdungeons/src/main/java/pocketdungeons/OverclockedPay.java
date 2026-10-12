package pocketdungeons;

/**
 * Overclocked pays for speed (affix workshop 2026-10-11, row 3): a floor cleared within the par time of its
 * commit adds a few scrap to its pay. The bonus rides the existing floor pay path ({@link FloorPay#bonus},
 * {@link ScrapMath#floorPay}); it never touches the dealt step. The numbers are config knobs.
 *
 * <p>No Minecraft imports, so {@code OverclockedPayTest} runs with plain {@code javac}.
 */
final class OverclockedPay {

    private OverclockedPay() {}

    /**
     * The extra scrap for a floor cleared {@code nowTick - startedAtTick} ticks after its commit. Zero unless
     * the floor is Overclocked, the commit time is known ({@code startedAtTick > 0}), and the clear came at or
     * under par.
     */
    static int bonus(boolean overclocked, long startedAtTick, long nowTick, int parSeconds, int bonus) {
        if (!overclocked || startedAtTick <= 0 || nowTick < startedAtTick) {
            return 0;
        }
        long elapsedTicks = nowTick - startedAtTick;
        return elapsedTicks <= Math.max(1, parSeconds) * 20L ? Math.max(0, bonus) : 0;
    }

    /** The par as the title shows it: {@code m:ss}. */
    static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** The line under the Overclocked label on the floor title, or {@code null} when the bonus pays nothing. */
    static String titleLine(int parSeconds, int bonus) {
        if (bonus <= 0) {
            return null;
        }
        return "Under " + clock(parSeconds) + ": +" + bonus + " scrap";
    }
}
