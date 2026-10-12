package pocketdungeons;

/**
 * The Act 1 mine guardians' hardness dial (roadmap T41): counts climb without a cap on the dial but
 * never past their own ceilings or the mob cap, and tells speed up but never under two seconds.
 */
public class MineGuardianRulesTest {

    public static void main(String[] args) {
        // Counts rise with hardness and respect their per guardian ceilings.
        check(MineGuardianRules.bricksFor(1.0) == 2, "bricks at hardness 1.0: " + MineGuardianRules.bricksFor(1.0));
        check(MineGuardianRules.bricksFor(4.0) > MineGuardianRules.bricksFor(1.0), "bricks grow with hardness");
        check(MineGuardianRules.bricksFor(1000.0) == MineGuardianRules.MAX_BRICKS, "bricks stop at the ceiling");
        check(MineGuardianRules.wolvesFor(1.0) == 3, "wolves at hardness 1.0: " + MineGuardianRules.wolvesFor(1.0));
        check(MineGuardianRules.wolvesFor(4.0) > MineGuardianRules.wolvesFor(1.0), "wolves grow with hardness");
        check(MineGuardianRules.wolvesFor(1000.0) == MineGuardianRules.MAX_WOLVES, "wolves stop at the ceiling");
        check(MineGuardianRules.coffinsFor(1.0) == 2, "coffins at hardness 1.0: " + MineGuardianRules.coffinsFor(1.0));
        check(MineGuardianRules.coffinsFor(4.0) > MineGuardianRules.coffinsFor(1.0), "coffins grow with hardness");
        check(MineGuardianRules.coffinsFor(1000.0) == MineGuardianRules.MAX_COFFINS, "coffins stop at the ceiling");

        // The dial climbs the way EndlessMineRules drives it: guardians 1 to 9 get strictly harder.
        double previous = 0.0;
        for (int number = 1; number <= 9; number++) {
            double hardness = EndlessMineRules.hardness(number, 0.25, 0.5);
            check(hardness > previous, "hardness rises every guardian: " + hardness);
            check(MineGuardianRules.bricksFor(hardness) >= MineGuardianRules.bricksFor(previous),
                    "bricks never shrink at guardian " + number);
            previous = hardness;
        }

        // Tells speed up but never under two seconds.
        check(MineGuardianRules.tellTicks(MineGuardianRules.TELL_BASE_TICKS, 1.0)
                        == MineGuardianRules.TELL_BASE_TICKS,
                "the first guardian tells at full length");
        check(MineGuardianRules.tellTicks(MineGuardianRules.TELL_BASE_TICKS, 2.0)
                        < MineGuardianRules.TELL_BASE_TICKS,
                "a harder guardian tells faster");
        check(MineGuardianRules.tellTicks(MineGuardianRules.TELL_BASE_TICKS, 1000.0)
                        == MineGuardianRules.TELL_FLOOR_TICKS,
                "the tell floor holds at any hardness");
        check(MineGuardianRules.TELL_FLOOR_TICKS >= 40, "the floor is two seconds");

        // The queen is smaller than the real Queen and capped.
        check(MineGuardianRules.queenScale(1.0) == 1.5, "queen scale at hardness 1.0");
        check(MineGuardianRules.queenScale(4.0) > MineGuardianRules.queenScale(1.0), "the queen grows");
        check(MineGuardianRules.queenScale(1000.0) == MineGuardianRules.QUEEN_MAX_SCALE, "the queen has a size cap");

        // The mob cap trims every count.
        check(MineGuardianRules.capped(8, 16) == 8, "under the cap the count stands");
        check(MineGuardianRules.capped(8, 4) == 4, "the cap trims");
        check(MineGuardianRules.capped(8, 0) == 0, "no slots, no mobs");

        System.out.println("MineGuardianRulesTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
