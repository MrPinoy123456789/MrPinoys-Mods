package pocketdungeons;

/**
 * The pure rules of the Kennels finale (roadmap T22, boss workshop 2026-10-11 row 4 and decision 4b): the
 * handler (the wave's pillager) starts a windup when the Alpha is at half health; at the end of the windup the
 * pack scales up and turns angry; killing the handler during the windup cancels it, and killing it afterwards
 * shrinks the pack back. No Minecraft imports, so {@code KennelsFinaleRulesTest} runs with plain {@code javac}.
 *
 * <p>The numbers are named constants here (layer 4 of the tune-knobs skill) rather than {@code pocketdungeons.json}
 * keys: a new config key needs every golden config fixture regenerated, which is not worth it for a first cut.
 * Promote them when the owner retunes them.
 */
final class KennelsFinaleRules {

    private KennelsFinaleRules() {}

    /** The windup starts when the Alpha's health is at or under this share of its maximum (decision 4b: half). */
    static final double TRIGGER_HEALTH_FRACTION = 0.5;

    /** Ticks between the handler raising its arm and the pack growing: 5 seconds, a long tell. */
    static final int WINDUP_TICKS = 100;

    /** What the wolves' scale is multiplied by while empowered. */
    static final double PACK_SCALE = 1.4;

    /**
     * Percent of extra maximum health an empowered wolf gains. Off by default (owner): scale and anger are the
     * effect. A positive value also heals the wolf by the gained amount.
     */
    static final int PACK_HEALTH_BONUS_PERCENT = 0;

    /** How often the angry state is refreshed while empowered, so the red eyes never lapse. */
    static final int ANGER_REFRESH_TICKS = 20;

    /** Where the fight stands. */
    enum Phase {
        /** The Alpha has not yet reached the trigger line. */
        WAITING,
        /** The handler has its arm up; the pack grows when the windup ends. */
        WINDUP,
        /** The pack is large and angry; killing the handler shrinks it. */
        EMPOWERED,
        /** Nothing more will happen: cancelled, shrunk back, or the handler was dead at the trigger. */
        SPENT
    }

    /** What the caller should do this tick. */
    enum Action {
        NONE,
        /** Raise the handler's arm, sound the horn, warn the party. */
        START_WINDUP,
        /** Scale the pack up and anger it. */
        EMPOWER,
        /** The handler died in the windup: lower nothing, grow nothing. */
        CANCEL,
        /** The handler died after the pack grew: put the pack back to normal size. */
        SHRINK
    }

    /** The new phase and the one thing to do about it. */
    record Step(Phase phase, Action action) {}

    /** Whether {@code health} of {@code max} is at or under {@code fraction} of the maximum. */
    static boolean atTrigger(double health, double max, double fraction) {
        return max > 0.0 && health <= max * fraction;
    }

    /**
     * One watch tick of the rule. {@code windupEnd} is the tick the windup ends (read only in
     * {@link Phase#WINDUP}); {@code alphaAlive} with its {@code health} and {@code max} decide the trigger
     * (read only in {@link Phase#WAITING}). If the handler is already dead when the line is crossed, nothing
     * happens, ever. A handler that dies on the very tick the windup ends cancels it.
     */
    static Step step(Phase phase, long now, long windupEnd, boolean handlerAlive, boolean alphaAlive,
                     double health, double max, double fraction) {
        switch (phase) {
            case WAITING:
                if (!alphaAlive || !atTrigger(health, max, fraction)) {
                    return new Step(Phase.WAITING, Action.NONE);
                }
                return handlerAlive ? new Step(Phase.WINDUP, Action.START_WINDUP) : new Step(Phase.SPENT, Action.NONE);
            case WINDUP:
                if (!handlerAlive) {
                    return new Step(Phase.SPENT, Action.CANCEL);
                }
                return now >= windupEnd ? new Step(Phase.EMPOWERED, Action.EMPOWER) : new Step(Phase.WINDUP, Action.NONE);
            case EMPOWERED:
                return handlerAlive ? new Step(Phase.EMPOWERED, Action.NONE) : new Step(Phase.SPENT, Action.SHRINK);
            default:
                return new Step(Phase.SPENT, Action.NONE);
        }
    }

    /** A wolf's scale while empowered, from the scale it had. */
    static double empoweredScale(double base) {
        return base * PACK_SCALE;
    }

    /** A wolf's maximum health while empowered, from the maximum it had and a bonus percent (0 or less: unchanged). */
    static double empoweredMaxHealth(double base, int bonusPercent) {
        return bonusPercent <= 0 ? base : base * (100 + bonusPercent) / 100.0;
    }

    /** The health a wolf keeps when its maximum falls back to {@code max}: never above it. */
    static double healthAfterShrink(double health, double max) {
        return Math.min(health, max);
    }

    /** The health a wolf has after its maximum grows from {@code oldMax} to {@code newMax}: it gains the difference. */
    static double healthAfterGrow(double health, double oldMax, double newMax) {
        return health + Math.max(0.0, newMax - oldMax);
    }

    /** The scoreboard team a run slot's empowered pack glows in. */
    static String teamName(int slot) {
        return "pd_pack_" + slot;
    }

    /** Whether a team name is one of ours (the stop sweep removes these). */
    static boolean isPackTeam(String name) {
        return name != null && name.startsWith("pd_pack_");
    }
}
