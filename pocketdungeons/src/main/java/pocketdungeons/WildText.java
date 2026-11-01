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
    static final String CLEARED = "The place is cleared. Take what is yours and use the Exit when you are ready.";
    static final String COLLAPSE_SOON = "This place will collapse soon. Take what you can and get out.";
    static final String COLLAPSE_NOW = "The ceiling is giving way. Leave now.";
    static final String RESET_SOON = "The place is closing soon. Gather your things.";

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
        List<String> lines = new ArrayList<>();
        lines.add(promise);
        switch (phase) {
            case READY -> lines.add(READY);
            case IN_PROGRESS -> lines.add(inside > 0 ? "In progress. " + inside + " inside." : "In progress.");
            case CLEARED -> lines.add("Cleared. Resets in " + duration(secondsToReset) + ".");
        }
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
