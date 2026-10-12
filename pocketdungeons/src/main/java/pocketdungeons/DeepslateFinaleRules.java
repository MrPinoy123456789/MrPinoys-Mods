package pocketdungeons;

/**
 * The pure rules of the Silent Deep's Hush (roadmap T31, boss workshop 2026-10-11 row 9): the finale runs under
 * the room's Heard meter, noise fills it, a full meter answers with darkness and a Warden whelp (never the
 * Warden, see {@link SculkOmen#wardenAllowed}), and the answer is announced a few seconds ahead. No Minecraft
 * imports, so {@code DeepslateFinaleRulesTest} runs with plain {@code javac}.
 *
 * <p>The meter itself is {@code PressureSources}' Heard meter, counted with {@link SculkOmen#heardAfter} and
 * {@link SculkOmen#answers}; this class only says how much each noise is worth and when to warn and answer.
 */
final class DeepslateFinaleRules {

    private DeepslateFinaleRules() {}

    /** The template of the room that listens (the manifest's {@code template}). */
    static final String ROOM_TEMPLATE = "pocketdungeons:rooms/silent_deep";

    /**
     * Ticks between the tell (a sound and a line) and the answer: 3 seconds, over the 2 second floor of the
     * finale-script fairness rules.
     */
    static final int TELL_TICKS = 60;

    /** The most whelps one answer may send, whatever the knob says. */
    static final int MAX_WHELPS = 4;

    /** The kinds of noise that fill the meter. */
    enum Noise {
        /** A sculk sensor in the room went active. */
        PULSE,
        /** A member shot a bow or crossbow. */
        BOW,
        /** A member took a hit. */
        HIT,
        /** A mob of the finale died. */
        DEATH,
        /** A member sprinted for a second inside the room. */
        SPRINT
    }

    /** What each kind of noise adds, in meter units; the knobs, gathered so the arithmetic is testable. */
    record Rises(int pulse, int bow, int hit, int death, int sprint) {

        /** Units for one event of this kind; never negative. */
        int of(Noise noise) {
            int each = switch (noise) {
                case PULSE -> pulse;
                case BOW -> bow;
                case HIT -> hit;
                case DEATH -> death;
                case SPRINT -> sprint;
            };
            return Math.max(0, each);
        }

        /** Units for {@code count} events of this kind. A negative count is none. */
        int of(Noise noise, int count) {
            return of(noise) * Math.max(0, count);
        }
    }

    /**
     * Units the slow rise adds at the {@code fightSecond}-th second of the fight: one on every
     * {@code everySeconds}-th second, none if {@code everySeconds} is 0 or less. Quiet fighting still fills the
     * meter, only slowly.
     */
    static int quietRise(long fightSecond, int everySeconds) {
        return everySeconds > 0 && fightSecond > 0 && fightSecond % everySeconds == 0 ? 1 : 0;
    }

    /** Whether the meter has reached the ceiling and the room answers. */
    static boolean full(int heard, int ceiling) {
        return SculkOmen.answers(heard, ceiling);
    }

    /** Whether the meter is three quarters full (a gentle warning, once per fill), but not yet full. */
    static boolean nearlyFull(int heard, int ceiling) {
        return ceiling > 0 && !full(heard, ceiling) && heard * 4 >= ceiling * 3;
    }

    /** The tick the answer lands, given the tick the tell began. */
    static long answerTick(long tellTick) {
        return tellTick + TELL_TICKS;
    }

    /** Whether the answer is due: the tell has run its full length. */
    static boolean answerDue(long now, long answerTick) {
        return now >= answerTick;
    }

    /** The whelps one answer sends: the knob, kept between 1 and {@link #MAX_WHELPS}. */
    static int whelps(int knob) {
        return Math.max(1, Math.min(MAX_WHELPS, knob));
    }
}
