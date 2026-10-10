package pocketdungeons;

/**
 * Pure-JDK regression for {@link ScrapMath}, the haul ledger: what a floor pays into the
 * haul, what a level costs and banking the haul into the compass bar across rising prices, what a failed
 * dungeon keeps, and the migration from the old spendable pool. The scrap curve is design pass 2026-10-09, Q2.
 */
public class ScrapMathTest {

    public static void main(String[] args) {
        testFloorPay();
        testActAndFinalBonus();
        testLevelCost();
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
        check(ScrapMath.floorPay(0, 10, 10, 4), 0, "a bonus never pays a floor dealt no step");
    }

    /** The bonus rides on the step; below the compass the whole pay is halved, never under 1. */
    private static void testActAndFinalBonus() {
        check(ScrapMath.actBonus(1, 1), 0, "Act 1 adds nothing");
        check(ScrapMath.actBonus(2, 1), 1, "Act 2 adds 1");
        check(ScrapMath.actBonus(5, 1), 4, "Act 5 adds 4");
        check(ScrapMath.actBonus(5, 0), 0, "a per-act bonus of 0 turns it off");
        // Act 5 final floor, step 3: 3 + 4 + 1 = 8 at the compass.
        check(ScrapMath.floorPay(3, 30, 25, 5, 50), 8, "a deep final floor at the compass pays step plus bonuses");
        check(ScrapMath.floorPay(3, 30, 40, 5, 50), 4, "below the compass it pays half: 8 becomes 4");
        check(ScrapMath.floorPay(1, 3, 40, 0, 50), 1, "half of 1 is still 1");
        check(ScrapMath.floorPay(2, 3, 40, 1, 50), 1, "half of 3 rounds down to 1");
        check(ScrapMath.floorPay(3, 3, 40, 5, 0), 1, "0 percent still pays the minimum of 1");
        check(ScrapMath.floorPay(3, 3, 40, 5, 100), 8, "100 percent below the compass pays in full");
        check(ScrapMath.endlessBonus(1, 4, 4), 0, "the first Mine floor adds nothing");
        check(ScrapMath.endlessBonus(4, 4, 4), 1, "four floors down adds 1");
        check(ScrapMath.endlessBonus(16, 4, 4), 4, "sixteen down adds 4");
        check(ScrapMath.endlessBonus(60, 4, 4), 4, "and no more than the cap");
        check(ScrapMath.endlessBonus(9, 4, 0), 0, "a cap of 0 turns it off");
    }

    /** The table from the design reply: compass 1 costs 4, 5 costs 5, 10 costs 7, 25 costs 12, 50 costs 20. */
    private static void testLevelCost() {
        check(ScrapMath.levelCost(0, 4, 3), 4, "a new player pays 4");
        check(ScrapMath.levelCost(1, 4, 3), 4, "compass 1 pays 4");
        check(ScrapMath.levelCost(2, 4, 3), 4, "compass 2 pays 4");
        check(ScrapMath.levelCost(3, 4, 3), 5, "compass 3 pays 5");
        check(ScrapMath.levelCost(5, 4, 3), 5, "compass 5 pays 5");
        check(ScrapMath.levelCost(10, 4, 3), 7, "compass 10 pays 7");
        check(ScrapMath.levelCost(15, 4, 3), 9, "compass 15 pays 9");
        check(ScrapMath.levelCost(20, 4, 3), 10, "compass 20 pays 10");
        check(ScrapMath.levelCost(25, 4, 3), 12, "compass 25 pays 12");
        check(ScrapMath.levelCost(50, 4, 3), 20, "compass 50 pays 20");
        check(ScrapMath.levelCost(50, 5, 1), 55, "the knobs move it");
        check(ScrapMath.levelCost(-3, 4, 3), 4, "a negative compass is a new player");
        // From compass 1 to 25 the table totals 188 (the reply's worked check).
        check(ScrapMath.totalScrap(25, 0) - ScrapMath.totalScrap(1, 0), 188, "compass 1 to 25 costs 188 in all");
        check(ScrapMath.totalScrap(3, 2), 4 + 4 + 4 + 2, "the bar counts toward the total");
    }

    private static void testBank() {
        ScrapMath.Banked none = ScrapMath.bank(12, 0, 0);
        check(none.compass(), 12, "banking nothing changes nothing");
        check(none.progress(), 0, "and leaves the bar");
        check(none.gained(), 0, "and gains no level");

        // Compass 3 costs 5.
        ScrapMath.Banked inside = ScrapMath.bank(3, 1, 3);
        check(inside.compass(), 3, "1 + 3 stays inside a bar of 5");
        check(inside.progress(), 4, "and fills it to 4");
        check(inside.gained(), 0, "with no level");

        ScrapMath.Banked one = ScrapMath.bank(3, 3, 2);
        check(one.compass(), 4, "3 + 2 fills the bar of 5: level 4");
        check(one.progress(), 0, "and starts the next bar empty");
        check(one.gained(), 1, "one level");

        // Compass 3 then 4 both cost 5: 4 + 8 = 12 is two levels with 2 over.
        ScrapMath.Banked carry = ScrapMath.bank(3, 4, 8);
        check(carry.compass(), 5, "4 + 8 = 12 is two levels");
        check(carry.progress(), 2, "with 2 carried into the bar");
        check(carry.gained(), 2, "two levels");

        // From nothing the first levels cost 4 each: 17 = 4 + 4 + 4 + 5 = 17, four levels, nothing over.
        ScrapMath.Banked big = ScrapMath.bank(0, 0, 17);
        check(big.compass(), 4, "17 from nothing crosses rising prices to level 4");
        check(big.progress(), 0, "with nothing over");
        check(big.gained(), 4, "four levels");
        check(ScrapMath.bank(0, 0, 16).compass(), 3, "16 is three levels at 4 and 4 and 4, then 4 short of the fourth");
        check(ScrapMath.bank(0, 0, 16).progress(), 4, "with 4 in the bar");
        check(ScrapMath.bank(7, 0, -4).compass(), 7, "a negative haul banks nothing");
        // A bar that already meets the price (saved under the old flat 5) settles into a level.
        ScrapMath.Banked old = ScrapMath.bank(1, 4, 0);
        check(old.compass(), 2, "a saved bar of 4 at compass 1 meets the price of 4");
        check(old.progress(), 0, "and settles");
        // Banking is the same whether done at once or in two goes.
        ScrapMath.Banked split = ScrapMath.bank(8, 0, 13);
        ScrapMath.Banked first = ScrapMath.bank(8, 0, 6);
        ScrapMath.Banked second = ScrapMath.bank(first.compass(), first.progress(), 7);
        check(second.compass(), split.compass(), "banking in two goes reaches the same level");
        check(second.progress(), split.progress(), "and the same bar");
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
    }

    private static void testMigration() {
        // The pool era priced a level at a flat 5; the pool's overhang past the compass becomes the bar,
        // held under the new price of the compass it lands on.
        check(ScrapMath.migratedProgress(16, 3), 1, "a never-spent pool of 16 at compass 3 keeps 1");
        check(ScrapMath.migratedProgress(0, 12), 0, "a migrated player with an empty pool lands on an empty bar");
        check(ScrapMath.migratedProgress(70, 12), ScrapMath.levelCost(12) - 1, "a large pool clamps to the bar: one short of a level");
        check(ScrapMath.migratedProgress(5, 12), 0, "a spent-down pool never goes negative");
        check(ScrapMath.migratedProgress(-4, 0), 0, "a negative pool is nothing");
    }

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
