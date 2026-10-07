package pocketdungeons;

/**
 * Pure-JDK regression for {@link ScrapMath}: chart level from the scrap
 * pool, the permanent high water mark, and the spend check and refusal
 * line. No Minecraft classpath, run from {@code tasks.test}.
 */
public class ScrapMathTest {

    public static void main(String[] args) {
        testChartLevel();
        testScrapIntoChart();
        testHighestCharts();
        testSpend();
        System.out.println("ScrapMathTest passed");
    }

    private static void testChartLevel() {
        check(ScrapMath.chartLevel(0), 0, "no scrap, no charts");
        check(ScrapMath.chartLevel(4), 0, "four scrap is not a chart yet");
        check(ScrapMath.chartLevel(5), 1, "five scrap is one chart");
        check(ScrapMath.chartLevel(24), 4, "24 scrap is 4 charts");
        check(ScrapMath.chartLevel(120), 24, "compass 25 needs 24 charts per the plan's pace check");
        check(ScrapMath.chartLevel(-3), 0, "a negative pool shows no level");
    }

    private static void testScrapIntoChart() {
        check(ScrapMath.scrapIntoChart(7), 2, "7 scrap is 1 chart and 2 into the next");
        check(ScrapMath.scrapIntoChart(5), 0, "a whole chart starts at zero");
    }

    private static void testHighestCharts() {
        check(ScrapMath.highestCharts(4, 1, 0), 1, "the fifth scrap earns the first chart");
        check(ScrapMath.highestCharts(4, 1, 9), 9, "earning can never lower the permanent high");
        check(ScrapMath.highestCharts(0, 0, 3), 3, "no earn, no change");
        // Spending drains the pool, not the high: a member at 3 charts who spent
        // down to 1 scrap and earns 1 still reads 3.
        check(ScrapMath.highestCharts(1, 1, 3), 3, "spent scrap keeps the high mark");
    }

    private static void testSpend() {
        check(!ScrapMath.canSpend(3, 4), "3 carried cannot pay 4");
        check(ScrapMath.canSpend(4, 4), "exactly enough pays");
        check(ScrapMath.canSpend(7, 4), "more than enough pays");
        check(ScrapMath.notEnough(4, 3).equals("Not enough scrap: 4 needed, 3 carried."),
                "the refusal line");
    }

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " got " + actual);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
