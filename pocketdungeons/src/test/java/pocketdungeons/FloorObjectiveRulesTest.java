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
        testRelicRoll();
        testRelicCell();
        testRelicBonusRolls();
        testRelicLost();
        testRelicIsParsedAndNeverTheGate();
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

    // ---- relic: greed, never the gate -------------------------------------------

    private static void testRelicRoll() {
        for (long salt = -50; salt < 50; salt++) {
            check(!FloorObjectiveRules.relicRolls(salt, 0.0), "chance 0 never rolls");
            check(!FloorObjectiveRules.relicRolls(salt, -1.0), "a negative chance never rolls");
            check(FloorObjectiveRules.relicRolls(salt, 1.0), "chance 1 always rolls");
            check(FloorObjectiveRules.relicRolls(salt, 0.25) == FloorObjectiveRules.relicRolls(salt, 0.25),
                    "the roll is stable for a salt");
        }
        int hits = 0;
        for (long salt = 0; salt < 4000; salt++) {
            if (FloorObjectiveRules.relicRolls(salt * 7919L, 0.25)) {
                hits++;
            }
        }
        check(hits > 800 && hits < 1200, "a quarter chance lands near a quarter: " + hits + " of 4000");
    }

    private static void testRelicCell() {
        check(FloorObjectiveRules.relicCell(0, 0, 5L), -1);
        check(FloorObjectiveRules.relicCell(0, 3, 5L), 2);
        check(FloorObjectiveRules.relicCell(4, 3, 6L), 2);
        check(FloorObjectiveRules.relicCell(1, 3, -7L), 0);
        for (long salt = -20; salt < 20; salt++) {
            int index = FloorObjectiveRules.relicCell(3, 5, salt);
            check(index >= 0 && index < 3, "a branch is preferred whenever there is one: " + index);
        }
    }

    private static void testRelicBonusRolls() {
        // The default: the relic adds 100 to the Loot percent, so an unwagered floor of 3 chests pays 3 more.
        check(FloorObjectiveRules.relicBonusRolls(3, 100, 100), 3);
        // Loot 150 percent: 3 chests round to 5 (4.5), with the relic 250 percent is 8 (7.5): three more.
        check(FloorObjectiveRules.relicBonusRolls(3, 150, 100), 3);
        check(FloorObjectiveRules.relicBonusRolls(4, 100, 50), 2);
        check(FloorObjectiveRules.relicBonusRolls(3, 100, 0), 0);
        check(FloorObjectiveRules.relicBonusRolls(0, 100, 100), 0);
        check(FloorObjectiveRules.relicBonusRolls(3, 100, -40), 0);
        // Never negative, whatever the rounding does.
        for (int base = 0; base < 12; base++) {
            for (int loot = 100; loot <= 400; loot += 50) {
                for (int relic = 0; relic <= 400; relic += 25) {
                    check(FloorObjectiveRules.relicBonusRolls(base, loot, relic) >= 0, "never negative");
                }
            }
        }
    }

    private static void testRelicLost() {
        check(FloorObjectiveRules.relicLost(false, false, false, false), "nowhere: put it back");
        check(!FloorObjectiveRules.relicLost(false, true, false, false), "in a pack: fine");
        check(!FloorObjectiveRules.relicLost(false, false, true, false), "on the ground: fine");
        check(!FloorObjectiveRules.relicLost(false, false, false, true), "in a pot: fine");
        check(!FloorObjectiveRules.relicLost(true, false, false, false), "delivered: it paid, it is gone, never put back");
    }

    private static void testRelicIsParsedAndNeverTheGate() {
        check(FloorObjective.of("relic") == FloorObjective.RELIC, "the node word reads");
        check(FloorObjective.of("spawners") == FloorObjective.SPAWNERS, "spawners still read");
        // A relic floor that could not place its plinth runs as a spawner floor, like every objective.
        check(FloorObjectiveRules.effective(FloorObjective.RELIC, 0, 1) == FloorObjective.SPAWNERS,
                "no plinth, no relic floor");
        check(FloorObjectiveRules.effective(FloorObjective.RELIC, 1, 1) == FloorObjective.RELIC, "a plinth keeps it");
        // The node accepts the word, and only the final keeps spawners by the validator (checked in UnchartedGenTest).
        new DungeonDef.Node("x", "X", 2, "", "", java.util.List.of(), 0, false, DungeonDef.Node.LIGHT_LIT,
                java.util.List.of(), DungeonDef.Node.INHERIT_NODE_ROOMS, 5, "", DungeonDef.Node.OBJECTIVE_RELIC);
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
