package pocketdungeons;

/**
 * Pure-JDK regression for {@link FloorObjectiveRules}: the fallback rule
 * (nothing placed means a spawner floor), the effective quota clamp, the
 * heist and claim quotas, the haunt's wave tremor and a claim floor's bonus
 * rolls.
 */
public class FloorObjectiveRulesTest {

    public static void main(String[] args) {
        testEffective();
        testEffectiveQuota();
        testStrongboxes();
        testVeins();
        testWaveAt();
        testBonusRolls();
        System.out.println("FloorObjectiveRulesTest passed");
    }

    // ---- the fallback rule ----------------------------------------------------

    private static void testEffective() {
        // Nothing placed: the floor runs its spawner gate, whatever it declared.
        for (FloorObjective declared : FloorObjective.values()) {
            check(FloorObjectiveRules.effective(declared, 0, 3) == FloorObjective.SPAWNERS,
                    "nothing placed falls back to spawners: " + declared);
            check(FloorObjectiveRules.effective(declared, 0, 0) == FloorObjective.SPAWNERS,
                    "nothing placed and nothing required falls back too: " + declared);
        }
        // One or more placed keeps the declared objective.
        check(FloorObjectiveRules.effective(FloorObjective.HEIST, 1, 3) == FloorObjective.HEIST,
                "one strongbox placed keeps the heist");
        check(FloorObjectiveRules.effective(FloorObjective.CLAIM, 5, 3) == FloorObjective.CLAIM,
                "more placed than required keeps the claim");
        check(FloorObjectiveRules.effective(FloorObjective.HAUNT, 2, 2) == FloorObjective.HAUNT,
                "the haunt keeps its own");
    }

    private static void testEffectiveQuota() {
        check(FloorObjectiveRules.effectiveQuota(3, 0), 0);
        check(FloorObjectiveRules.effectiveQuota(3, -2), 0);
        check(FloorObjectiveRules.effectiveQuota(3, 1), 1);
        check(FloorObjectiveRules.effectiveQuota(3, 2), 2);
        check(FloorObjectiveRules.effectiveQuota(3, 3), 3);
        // Placed more than required clamps down to the requirement.
        check(FloorObjectiveRules.effectiveQuota(3, 9), 3);
        check(FloorObjectiveRules.effectiveQuota(1, 5), 1);
    }

    // ---- heist: strongboxes ---------------------------------------------------

    private static void testStrongboxes() {
        check(FloorObjectiveRules.strongboxes(2, 1), 2);
        check(FloorObjectiveRules.strongboxes(2, 2), 2);
        check(FloorObjectiveRules.strongboxes(2, 3), 3);
        check(FloorObjectiveRules.strongboxes(2, 4), 3);
        check(FloorObjectiveRules.strongboxes(2, 5), 4);
        // A member count of zero cannot pull the base down.
        check(FloorObjectiveRules.strongboxes(2, 0), 2);
        check(FloorObjectiveRules.strongboxes(0, 5), 2);
    }

    // ---- claim: veins ---------------------------------------------------------

    private static void testVeins() {
        check(FloorObjectiveRules.veins(3, 1, 1), 4);
        check(FloorObjectiveRules.veins(3, 1, 2), 4);
        // A party of three or more earns the extra vein.
        check(FloorObjectiveRules.veins(3, 1, 3), 5);
        check(FloorObjectiveRules.veins(3, 0, 4), 4);
        check(FloorObjectiveRules.veins(0, 0, 1), 0);
    }

    // ---- haunt: the wave tremor ------------------------------------------------

    private static void testWaveAt() {
        check(!FloorObjectiveRules.waveAt(0, 5), "no tremor, no wave");
        check(!FloorObjectiveRules.waveAt(4, 5), "under the tremor");
        check(FloorObjectiveRules.waveAt(5, 5), "at the tremor the wave comes");
        check(FloorObjectiveRules.waveAt(9, 5), "past the tremor");
        check(FloorObjectiveRules.waveAt(0, 0), "a zero tremor waves at once");
    }

    // ---- claim: bonus rolls ----------------------------------------------------

    private static void testBonusRolls() {
        check(FloorObjectiveRules.bonusRolls(0, 3), 0);
        check(FloorObjectiveRules.bonusRolls(3, 3), 0);
        check(FloorObjectiveRules.bonusRolls(2, 3), 0);
        check(FloorObjectiveRules.bonusRolls(5, 3), 2);
        check(FloorObjectiveRules.bonusRolls(5, 0), 5);
    }

    // ---- helpers ----------------------------------------------------------------

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
