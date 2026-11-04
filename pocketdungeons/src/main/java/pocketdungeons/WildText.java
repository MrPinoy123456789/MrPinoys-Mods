package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * Every player visible line wild mode says, kept pure so {@code WildTextTest} can pin the wording (short, no dashes).
 */
final class WildText {

    /** Said for bare {@code /dungeon} in wild mode. */
    static final String FIND_A_LODESTONE = "Find a lodestone out in the wild and right click it.";

    /** Said for {@code /dungeon exit} inside a wild dungeon. */
    static final String USE_THE_EXIT_BLOCK = "Use an Exit block to leave.";

    /** Said for {@code /dungeon exit} outside a wild dungeon. */
    static final String NOT_INSIDE = "You are not inside a dungeon.";

    static final String ENTER = "Enter";
    static final String NOT_NOW = "Not now";
    static final String LEAVE = "Leave";
    static final String STAY = "Stay";
    static final String LEAVE_TITLE = "Leave?";
    static final String KEEP_WHAT_YOU_CARRY = "You keep what you carry.";
    static final String EXIT_TITLE = "Exit";
    static final String READY = "Ready.";
    static final String CLEARED = "Cleared. Take what is yours, then use the Exit.";
    static final String COLLAPSE_SOON = "This place collapses soon. Take what you can and get out.";
    static final String COLLAPSE_NOW = "The ceiling is giving way. Leave now.";
    static final String RESET_SOON = "The place is closing soon. Gather your things.";

    // Ominous lodestones (decision 2026-11-04): an Ominous Bottle used on a Ready lodestone.
    static final String OMINOUS = "Ominous.";
    static final String OMEN_TAKEN = "Ominous now. The next run here is harder and pays more.";
    static final String OMEN_ALREADY = "Already ominous.";
    static final String OMEN_NOT_READY = "Only a Ready lodestone takes the omen.";

    /** Said to everyone inside when the boss falls and Valor is paid (the amount follows). */
    static String bossValor(int valor) {
        return "+" + valor + " Valor for the kill.";
    }

    /** "Last cleared by Aurelia, 2h ago.", or {@code null} when nobody has cleared it. */
    static String lastCleared(String by, long secondsAgo) {
        if (by == null || by.isBlank()) {
            return null;
        }
        return "Last cleared by " + by + ", " + duration(secondsAgo) + " ago.";
    }

    /** The last stretch of a lodestone's life, in seconds, in which the menu says when it collapses. */
    static final long COLLAPSE_NOTICE_SECONDS = 24L * 3600L;

    private WildText() {}

    /** The largest unit only: {@code 2h}, {@code 45m}, {@code 30s}. Anything under a second reads {@code 1s}. */
    static String duration(long seconds) {
        long s = Math.max(1L, seconds);
        if (s >= 86400L) {
            return (s / 86400L) + "d";
        }
        if (s >= 3600L) {
            return (s / 3600L) + "h";
        }
        if (s >= 60L) {
            return (s / 60L) + "m";
        }
        return s + "s";
    }

    /**
     * The body lines of the screen seen from outside: the promise, then where the lodestone stands.
     *
     * @param secondsToReset seconds until a cleared dungeon resets (read only when cleared)
     * @param secondsToCollapse seconds until the lodestone collapses; the line shows only inside the last day
     */
    static List<String> outsideBody(String promise, WildState.Phase phase, int inside, long secondsToReset,
                                    long secondsToCollapse) {
        return outsideBody(promise, phase, inside, secondsToReset, secondsToCollapse, false);
    }

    /** As above; an ominous lodestone says so before its state ("Ominous. Ready."). */
    static List<String> outsideBody(String promise, WildState.Phase phase, int inside, long secondsToReset,
                                    long secondsToCollapse, boolean ominous) {
        List<String> lines = new ArrayList<>();
        lines.add(promise);
        String state = switch (phase) {
            case READY -> READY;
            case IN_PROGRESS -> inside > 0 ? "In progress. " + inside + " inside." : "In progress.";
            case CLEARED -> "Cleared. Resets in " + duration(secondsToReset) + ".";
        };
        lines.add(ominous ? OMINOUS + " " + state : state);
        if (secondsToCollapse <= COLLAPSE_NOTICE_SECONDS) {
            lines.add("Collapses in " + duration(secondsToCollapse) + ".");
        }
        return lines;
    }

    /** The one body line of the Exit block that stands in a cleared dungeon. */
    static String clearExitBody(long secondsToReset) {
        return "Cleared. Resets in " + duration(secondsToReset) + ".";
    }
}
