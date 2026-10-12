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

        // Act 2: the sentry's meter fills faster as hardness climbs but has a floor.
        check(MineGuardianRules.sentryCeiling(1.0, 40) == 40, "the first sentry listens at the knob's pace");
        check(MineGuardianRules.sentryCeiling(4.0, 40) < 40, "a later sentry answers sooner");
        check(MineGuardianRules.sentryCeiling(1000.0, 40) == Math.max(
                        MineGuardianRules.SENTRY_MIN_CEILING, 40 / 4),
                "the ceiling never under a quarter of the base");

        // Act 2: vents grow with hardness, the cycle shortens, strays multiply.
        check(MineGuardianRules.ventsFor(1.0) == 4, "vents at hardness 1.0: " + MineGuardianRules.ventsFor(1.0));
        check(MineGuardianRules.ventsFor(4.0) > MineGuardianRules.ventsFor(1.0), "vents grow with hardness");
        check(MineGuardianRules.ventsFor(1000.0) == MineGuardianRules.MAX_VENTS, "vents stop at the ceiling");
        check(MineGuardianRules.ventPeriodTicks(160, 1.0) == 160, "the first Foreman's vents run at base");
        check(MineGuardianRules.ventPeriodTicks(160, 2.0) < 160, "a later Foreman's vents run faster");
        check(MineGuardianRules.ventPeriodTicks(160, 1000.0) == CopperClockRules.MIN_PERIOD_TICKS,
                "the vent floor is tell plus burn");
        check(MineGuardianRules.straysFor(1.0) == 1, "the first Frostbite hunts alone");
        check(MineGuardianRules.straysFor(3.0) > 1, "a later Frostbite brings kin");
        check(MineGuardianRules.straysFor(1000.0) == MineGuardianRules.MAX_STRAYS, "strays stop at the ceiling");

        System.out.println("MineGuardianRulesTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
