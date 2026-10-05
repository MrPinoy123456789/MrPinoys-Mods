package pocketdungeons;

/**
 * Dungeon structure W7b: the pure rules of the Act 5 capstone, Herobrine (Steve, corrupted). No
 * Minecraft imports, so {@code BossRulesTest} runs with plain {@code javac}. {@code HerobrineFight}
 * reads the world and acts on them.
 *
 * <h2>The fight</h2>
 * Steve fights in three phases, set by his health fraction: melee (above two thirds), summons
 * (above one third: ender and wither skeleton adds), then blink (he teleports around the room like
 * an enderman). He cannot be killed. The fight ends in Alex's rescue, which fires on whichever
 * comes first: a member drops to {@link #LOW_PLAYER_FRACTION} of their health, a member would have
 * died, Steve is brought to {@link #RESCUE_BOSS_FRACTION} of his health, or Steve would have died.
 * The pad stays shut until the rescue has played out.
 */
final class HerobrineRules {

    private HerobrineRules() {}

    /** Steve's max health for a solo party. */
    static final double BASE_HEALTH = 400.0;
    /** Extra max health per member past the first. */
    static final double HEALTH_PER_EXTRA_MEMBER = 200.0;
    /** Ceiling on the scaled max health. */
    static final double MAX_HEALTH = 1000.0;
    /** Steve's own attack damage for a solo party (the netherite sword adds its own on top). */
    static final double BASE_DAMAGE = 2.0;
    /** Extra attack damage per member past the first. */
    static final double DAMAGE_PER_EXTRA_MEMBER = 0.5;
    /** Ceiling on the scaled attack damage. */
    static final double MAX_DAMAGE = 5.0;

    /** Health fraction at or below which phase 2 (summons) begins. */
    static final double PHASE_TWO_AT = 2.0 / 3.0;
    /** Health fraction at or below which phase 3 (blink) begins. */
    static final double PHASE_THREE_AT = 1.0 / 3.0;
    /** Steve's health fraction at which the rescue fires if no member fell first. */
    static final double RESCUE_BOSS_FRACTION = 0.10;
    /** A member's health fraction at or below which the rescue fires. */
    static final double LOW_PLAYER_FRACTION = 0.25;

    /** Ticks between Steve's teleports in phase 3. */
    static final int BLINK_PERIOD_TICKS = 70;
    /** Ticks between summon waves in phases 2 and 3. */
    static final int SUMMON_PERIOD_TICKS = 300;
    /** Most adds alive at once. */
    static final int MAX_ADDS = 8;

    /** The three phases, in order. */
    enum Phase {
        MELEE(1), SUMMONS(2), BLINK(3);

        final int number;

        Phase(int number) {
            this.number = number;
        }
    }

    /** Steve's max health for a party of {@code party}. */
    static double maxHealth(int party) {
        return Math.min(MAX_HEALTH, BASE_HEALTH + HEALTH_PER_EXTRA_MEMBER * (Math.max(1, party) - 1));
    }

    /** Steve's base attack damage for a party of {@code party}. */
    static double attackDamage(int party) {
        return Math.min(MAX_DAMAGE, BASE_DAMAGE + DAMAGE_PER_EXTRA_MEMBER * (Math.max(1, party) - 1));
    }

    /** The phase at {@code health} of {@code maxHealth}. A bad max health reads as the first phase. */
    static Phase phase(double health, double maxHealth) {
        if (!(maxHealth > 0)) {
            return Phase.MELEE;
        }
        double fraction = health / maxHealth;
        if (fraction <= PHASE_THREE_AT) {
            return Phase.BLINK;
        }
        if (fraction <= PHASE_TWO_AT) {
            return Phase.SUMMONS;
        }
        return Phase.MELEE;
    }

    /** Whether a member at {@code health} of {@code maxHealth} counts as low (at or below a quarter). */
    static boolean playerLow(double health, double maxHealth) {
        return maxHealth > 0 && health <= maxHealth * LOW_PLAYER_FRACTION;
    }

    /** Whether Steve at {@code health} of {@code maxHealth} has been brought down to the rescue point. */
    static boolean bossLow(double health, double maxHealth) {
        return maxHealth > 0 && health <= maxHealth * RESCUE_BOSS_FRACTION;
    }

    /**
     * Whether the rescue should fire now: not yet fired, and a member is low or Steve is low. A
     * would-be death of either is handled by the caller, which cancels it and passes {@code true}
     * for the matching flag.
     */
    static boolean shouldRescue(boolean alreadyFired, boolean anyPlayerLow, boolean bossLow) {
        return !alreadyFired && (anyPlayerLow || bossLow);
    }

    /** How many adds a wave in {@code phase} brings for {@code party}, given {@code alive} already standing. */
    static int addsToSpawn(Phase phase, int party, int alive) {
        if (phase == Phase.MELEE) {
            return 0;
        }
        int wave = (phase == Phase.BLINK ? 3 : 2) + Math.max(1, party) - 1;
        return Math.max(0, Math.min(wave, MAX_ADDS - Math.max(0, alive)));
    }

    /** The pad's answer: {@code null} once the rescue has played out, else the line to show. */
    static String padRefusal(boolean rescueDone) {
        return rescueDone ? null : "The pad does not answer. Something here is not finished with you.";
    }

    /** Whether a member in the room should start the fight now. */
    static boolean shouldStart(boolean finalFloor, boolean alreadyStarted, int membersInRoom) {
        return finalFloor && !alreadyStarted && membersInRoom > 0;
    }
}
