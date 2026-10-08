package pocketdungeons;

/**
 * Pure-JDK regression for {@link ScrapMath}, the haul ledger: what a floor pays into the
 * haul, banking the haul into the compass bar, what a failed dungeon keeps, and the
 * migration from the old spendable pool.
 */
public class ScrapMathTest {

    public static void main(String[] args) {
        testFloorPay();
        testBank();
        testKeptOnFail();
        testMigration();
        System.out.println("ScrapMathTest passed");
    }

    private static void testFloorPay() {
        for (int step = 1; step <= 3; step++) {
            check(ScrapMath.floorPay(step, 10, 10), step, "a floor at the compass pays its step " + step);
            check(ScrapMath.floorPay(step, 11, 10), step, "a floor above the compass pays its step " + step);
            check(ScrapMath.floorPay(step, 9, 10), 1, "a floor below the compass pays 1, step " + step);
            check(ScrapMath.floorPay(step, 1, 25), 1, "a far lower floor still pays 1, step " + step);
        }
        check(ScrapMath.floorPay(0, 10, 10), 0, "a floor dealt no step pays nothing at the compass");
        check(ScrapMath.floorPay(0, 5, 10), 0, "and nothing below it");
        check(ScrapMath.floorPay(-2, 10, 10), 0, "a negative step pays nothing");
    }

    private static void testBank() {
        ScrapMath.Banked none = ScrapMath.bank(12, 0, 0);
        check(none.compass(), 12, "banking nothing changes nothing");
        check(none.progress(), 0, "and leaves the bar");
        check(none.gained(), 0, "and gains no level");

        ScrapMath.Banked inside = ScrapMath.bank(3, 1, 3);
        check(inside.compass(), 3, "1 + 3 stays inside the bar");
        check(inside.progress(), 4, "and fills it to 4");
        check(inside.gained(), 0, "with no level");

        ScrapMath.Banked one = ScrapMath.bank(3, 3, 2);
        check(one.compass(), 4, "3 + 2 fills the bar: level 4");
        check(one.progress(), 0, "and starts the next bar empty");
        check(one.gained(), 1, "one level");

        ScrapMath.Banked carry = ScrapMath.bank(3, 4, 8);
        check(carry.compass(), 5, "4 + 8 = 12 is two levels");
        check(carry.progress(), 2, "with 2 carried into the bar");
        check(carry.gained(), 2, "two levels");

        ScrapMath.Banked big = ScrapMath.bank(0, 0, 17);
        check(big.compass(), 3, "17 from nothing is three levels");
        check(big.progress(), 2, "and 2 over");
        check(ScrapMath.bank(7, 0, -4).compass(), 7, "a negative haul banks nothing");
    }

    private static void testKeptOnFail() {
        check(ScrapMath.keptOnFail(0, 50), 0, "an empty haul keeps nothing");
        check(ScrapMath.keptOnFail(1, 50), 0, "half of 1 rounds down to nothing");
        check(ScrapMath.keptOnFail(5, 50), 2, "half of 5 is 2");
        check(ScrapMath.keptOnFail(6, 50), 3, "half of 6 is 3");
        check(ScrapMath.keptOnFail(6, 0), 0, "0 percent keeps nothing");
        check(ScrapMath.keptOnFail(6, 100), 6, "100 percent keeps all");
        check(ScrapMath.keptOnFail(6, 250), 6, "above 100 clamps to all");
        check(ScrapMath.keptOnFail(6, -5), 0, "below 0 clamps to nothing");
        check(ScrapMath.keptOnFail(-3, 50), 0, "a negative haul keeps nothing");
        check(ScrapMath.FAIL_KEEP_PERCENT, 50, "the shipped default is half");
    }

    private static void testMigration() {
        check(ScrapMath.migratedProgress(16, 3), 1, "a never-spent pool of 16 at compass 3 keeps 1/5");
        check(ScrapMath.migratedProgress(0, 12), 0, "a migrated player with an empty pool lands on 0/5");
        check(ScrapMath.migratedProgress(70, 12), 4, "a large pool clamps to the bar");
        check(ScrapMath.migratedProgress(5, 12), 0, "a spent-down pool never goes negative");
        check(ScrapMath.migratedProgress(-4, 0), 0, "a negative pool is nothing");
    }

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " got " + actual);
        }
    }
}
