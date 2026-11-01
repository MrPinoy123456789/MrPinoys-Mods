package charter.config;

/**
 * The race combat passives as plain rules, so they can be tested without a server. Each answers for one race, named by
 * its node path (the kingdom's name in lower case, as in {@code race:azrak}). The game side reads the numbers from
 * {@link Knobs} and applies the answer. Every passive is mild on purpose; see docs/RACES.md.
 */
public final class RaceCombat {

    public static final String DWARF = "azrak";
    public static final String HUMAN = "aurelia";
    public static final String MERFOLK = "azurius";
    public static final String FAE = "faeloria";
    public static final String FAIRY = "everdelle";

    private RaceCombat() {
    }

    /** Dwarf Bedrock Stance: the knockback resistance to hold right now, 0 when it does not apply. */
    public static double sneakKnockbackResist(Knobs k, String race, boolean sneaking) {
        if (!k.raceCombat || !DWARF.equals(race) || !sneaking) return 0;
        return clamp(k.dwarfSneakKnockbackResist, 0, 1);
    }

    /** Merfolk Tidewrath: the extra attack damage to hold right now. */
    public static double waterDamageBonus(Knobs k, String race, boolean inWater) {
        if (!k.raceCombat || !MERFOLK.equals(race) || !inWater) return 0;
        return Math.max(0, k.merfolkWaterDamageBonus);
    }

    /** Human Rally: the extra attack damage for this many nearby allies (the player not counted). */
    public static double rallyBonus(Knobs k, String race, int allies) {
        if (!k.raceCombat || !HUMAN.equals(race) || allies <= 0) return 0;
        return Math.max(0, k.humanRallyPerAlly) * Math.min(allies, Math.max(0, k.humanRallyMaxAllies));
    }

    /**
     * Fae Glamour: whether a hostile mob that has this Fae as its target should lose it. Only at range, and never within
     * the grace window after the Fae attacked.
     */
    public static boolean glamourDropsTarget(Knobs k, String race, double distance, long ticksSinceFaeAttacked) {
        if (!k.raceCombat || !FAE.equals(race)) return false;
        if (ticksSinceFaeAttacked < k.faeGlamourGraceTicks) return false;
        return distance > k.faeGlamourRange;
    }

    /** Fairy Featherlight: fairies take no fall damage. */
    public static boolean ignoresFall(Knobs k, String race) {
        return k.raceCombat && FAIRY.equals(race);
    }

    /** Fairy Featherlight: whether a melee hit is dodged, given a roll in [0, 1). */
    public static boolean dodges(Knobs k, String race, double roll) {
        if (!k.raceCombat || !FAIRY.equals(race)) return false;
        return roll < clamp(k.fairyDodgeChance, 0, 1);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
