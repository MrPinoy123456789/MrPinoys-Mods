package pocketdungeons;

/**
 * Pure-JDK regression for roadmap T43, the Brood Chamber's "spawner as phase" order and numbers
 * ({@link BroodPhaseRules}): which cage frees which mechanic, the per mechanic headcounts, the drip
 * rate before and during the mother, her stats, and the mob cap arithmetic.
 */
public class BroodPhaseRulesTest {

    public static void main(String[] args) {
        testCageOrder();
        testHeadcounts();
        testMobCapRoom();
        testDripPace();
        testMother();
        testTellsHoldTwoSeconds();
        System.out.println("BroodPhaseRulesTest passed");
    }

    private static void testCageOrder() {
        check(BroodPhaseRules.ORDER.length == 4, "four cages, four mechanics");
        check(BroodPhaseRules.mechanicFor(0) == BroodPhaseRules.Mechanic.COFFINS,
                "the first cage frees the Ossuary's coffins");
        check(BroodPhaseRules.mechanicFor(1) == BroodPhaseRules.Mechanic.BRICKS,
                "the second frees the creeping bricks");
        check(BroodPhaseRules.mechanicFor(2) == BroodPhaseRules.Mechanic.DARK,
                "the third brings the dark");
        check(BroodPhaseRules.mechanicFor(3) == BroodPhaseRules.Mechanic.DRIP,
                "the fourth starts the drip");
        check(BroodPhaseRules.mechanicFor(4) == null && BroodPhaseRules.mechanicFor(-1) == null,
                "no mechanic outside the four cages");
        java.util.Set<BroodPhaseRules.Mechanic> seen = new java.util.HashSet<>();
        for (BroodPhaseRules.Mechanic mechanic : BroodPhaseRules.ORDER) {
            check(seen.add(mechanic), "every mechanic is freed exactly once: " + mechanic);
        }
    }

    private static void testHeadcounts() {
        for (int party = 1; party <= 12; party++) {
            check(BroodPhaseRules.coffinsFor(party) >= 1
                            && BroodPhaseRules.coffinsFor(party) <= BroodPhaseRules.MAX_COFFINS,
                    "coffins stay in range: " + party);
            check(BroodPhaseRules.brickSeedsFor(party) >= 1
                            && BroodPhaseRules.brickSeedsFor(party) <= BroodPhaseRules.MAX_BRICK_SEEDS,
                    "brick seeds stay in range: " + party);
            check(BroodPhaseRules.darkSpidersFor(party) >= 1
                            && BroodPhaseRules.darkSpidersFor(party) <= BroodPhaseRules.MAX_DARK_SPIDERS,
                    "dark spiders stay in range: " + party);
        }
        check(BroodPhaseRules.coffinsFor(1) == 2 && BroodPhaseRules.coffinsFor(4) == 4,
                "coffins grow with the party then cap");
        check(BroodPhaseRules.brickSeedsFor(1) == 4 && BroodPhaseRules.darkSpidersFor(1) == 3,
                "solo seeds and spiders");
        check(BroodPhaseRules.coffinsFor(0) == BroodPhaseRules.coffinsFor(1)
                        && BroodPhaseRules.coffinsFor(-5) == BroodPhaseRules.coffinsFor(1),
                "an empty party counts as one");
    }

    private static void testMobCapRoom() {
        check(BroodPhaseRules.room(24, 0) == 24, "an empty room has the whole cap");
        check(BroodPhaseRules.room(24, 20) == 4, "four slots left");
        check(BroodPhaseRules.room(24, 24) == 0 && BroodPhaseRules.room(24, 99) == 0,
                "a full room leaves nothing");
        check(BroodPhaseRules.room(0, 0) == 1, "a zero cap still clamps to one (the mother always comes)");
        check(BroodPhaseRules.room(8, -3) == 8, "a negative count cannot happen but is clamped anyway");
    }

    private static void testDripPace() {
        int base = 9 * 20;
        check(BroodPhaseRules.dripTicks(base, false) == base, "normal pace is the Lush interval");
        check(BroodPhaseRules.dripTicks(base, true) == base / 2, "the mother doubles the rate");
        check(BroodPhaseRules.dripTicks(60, true) == 40, "the fast drip never falls under two seconds");
        check(BroodPhaseRules.dripTicks(0, false) == 40, "a zero interval is still floored");
        check(BroodPhaseRules.dripTicks(5, false) == 40, "a silly interval is still floored");
    }

    private static void testMother() {
        check(BroodPhaseRules.motherHealth(1) == 80.0, "solo mother health");
        check(BroodPhaseRules.motherHealth(4) == 80.0 + 90.0, "the mother grows with the party");
        check(BroodPhaseRules.motherHealth(0) == BroodPhaseRules.motherHealth(1),
                "an empty party counts as one");
        check(BroodPhaseRules.motherDamage(1) > 0 && BroodPhaseRules.motherDamage(4) > BroodPhaseRules.motherDamage(1),
                "her damage grows too");
        check(BroodPhaseRules.MOTHER_SCALE > 2.0, "she reads as the mother, not a spider");
        check(!BroodPhaseRules.MOTHER_NAME.isEmpty() && !BroodPhaseRules.MOTHER_NAME.contains("Warden"),
                "her name is set and keeps the one Warden rule");
    }

    private static void testTellsHoldTwoSeconds() {
        check(BroodPhaseRules.windupTicks() >= 40, "the windup is never under two seconds");
        check(BroodPhaseRules.windupTicks() == KennelsFinaleRules.WINDUP_TICKS,
                "the windup is the Kennels beat's five seconds");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
