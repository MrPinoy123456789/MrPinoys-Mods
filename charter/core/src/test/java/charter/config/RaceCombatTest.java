package charter.config;

import static charter.Check.check;
import static charter.Check.eq;

/** The race combat passives: who gets which, and that none is strong. */
public final class RaceCombatTest {

    private RaceCombatTest() {
    }

    public static void run() {
        Knobs k = new Knobs();
        eq("a sneaking dwarf holds the knockback resistance", 0.4, RaceCombat.sneakKnockbackResist(k, RaceCombat.DWARF, true));
        eq("a standing dwarf holds none", 0.0, RaceCombat.sneakKnockbackResist(k, RaceCombat.DWARF, false));
        eq("a sneaking human holds none", 0.0, RaceCombat.sneakKnockbackResist(k, RaceCombat.HUMAN, true));
        k.dwarfSneakKnockbackResist = 5;
        eq("knockback resistance is capped at 1", 1.0, RaceCombat.sneakKnockbackResist(k, RaceCombat.DWARF, true));

        k = new Knobs();
        eq("a merfolk in water hits harder", 1.5, RaceCombat.waterDamageBonus(k, RaceCombat.MERFOLK, true));
        eq("a merfolk on land does not", 0.0, RaceCombat.waterDamageBonus(k, RaceCombat.MERFOLK, false));
        eq("a dwarf in water does not", 0.0, RaceCombat.waterDamageBonus(k, RaceCombat.DWARF, true));
        check("tidewrath is under Strength I (3 damage)", k.merfolkWaterDamageBonus < 3);

        eq("no allies, no rally", 0.0, RaceCombat.rallyBonus(k, RaceCombat.HUMAN, 0));
        eq("two allies", 1.0, RaceCombat.rallyBonus(k, RaceCombat.HUMAN, 2));
        eq("rally stops at three allies", 1.5, RaceCombat.rallyBonus(k, RaceCombat.HUMAN, 9));
        eq("a fae gets no rally", 0.0, RaceCombat.rallyBonus(k, RaceCombat.FAE, 3));
        check("the rally cap stays under Strength I", RaceCombat.rallyBonus(k, RaceCombat.HUMAN, 99) < 3);

        check("a far mob loses a fae", RaceCombat.glamourDropsTarget(k, RaceCombat.FAE, 10, 1000));
        check("a near mob keeps its target", !RaceCombat.glamourDropsTarget(k, RaceCombat.FAE, 3, 1000));
        check("a fae who just attacked is seen", !RaceCombat.glamourDropsTarget(k, RaceCombat.FAE, 10, 20));
        check("glamour is for fae only", !RaceCombat.glamourDropsTarget(k, RaceCombat.HUMAN, 10, 1000));

        check("a fairy ignores falls", RaceCombat.ignoresFall(k, RaceCombat.FAIRY));
        check("a fae does not ignore falls", !RaceCombat.ignoresFall(k, RaceCombat.FAE));
        check("a low roll dodges", RaceCombat.dodges(k, RaceCombat.FAIRY, 0.05));
        check("a high roll does not", !RaceCombat.dodges(k, RaceCombat.FAIRY, 0.5));
        check("a dwarf never dodges", !RaceCombat.dodges(k, RaceCombat.DWARF, 0.0));
        check("the dodge chance is small", k.fairyDodgeChance <= 0.1);

        k.raceCombat = false;
        eq("the master switch turns every passive off", 0.0, RaceCombat.waterDamageBonus(k, RaceCombat.MERFOLK, true)
                + RaceCombat.rallyBonus(k, RaceCombat.HUMAN, 3) + RaceCombat.sneakKnockbackResist(k, RaceCombat.DWARF, true));
        check("and fairies fall normally", !RaceCombat.ignoresFall(k, RaceCombat.FAIRY) && !RaceCombat.dodges(k, RaceCombat.FAIRY, 0));
    }
}
