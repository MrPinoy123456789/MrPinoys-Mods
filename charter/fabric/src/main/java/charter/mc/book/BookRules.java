package charter.mc.book;

import java.util.Locale;
import java.util.UUID;

/**
 * The pure rules of the Charter book, with no Minecraft types: who is handed one automatically ({@code bookGivenTo}),
 * and whose a carried copy is. The plain Java tests run these.
 */
public final class BookRules {

    /** The {@code bookGivenTo} knob. */
    public enum Mode {
        /** Everyone carries one, and a lost one comes back (the behaviour before the knob). */
        EVERYONE,
        /** Only players with no kingdom and no race; once they join or sign on, a lost book is not replaced. */
        NEWCOMERS,
        /** Nobody is handed one; the menu is reached through terminals, the command, or the button for a portable copy. */
        NONE
    }

    private BookRules() {
    }

    /** The mode a knob value names, in any case; anything else (or nothing) is {@link Mode#EVERYONE}, today's behaviour. */
    public static Mode mode(String value) {
        if (value == null) return Mode.EVERYONE;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "newcomers", "newcomer" -> Mode.NEWCOMERS;
            case "none", "nobody", "off" -> Mode.NONE;
            default -> Mode.EVERYONE;
        };
    }

    /** Whether a knob value is one of the three names. */
    public static boolean known(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("everyone") || v.equals("newcomers") || v.equals("none");
    }

    /** Whether a player is handed a book automatically (on joining, on respawn, and when a lost one is replaced). */
    public static boolean gives(Mode mode, boolean member, boolean hasRace) {
        return switch (mode) {
            case EVERYONE -> true;
            case NEWCOMERS -> !member && !hasRace;
            case NONE -> false;
        };
    }

    /** What the one-copy sweep does with a copy a player carries. */
    public enum Verdict { KEEP, ADOPT, FOREIGN }

    /**
     * A copy with no owner (made before books had owners) is adopted by whoever carries it; one owned by the carrier is
     * kept; one owned by someone else is removed.
     */
    public static Verdict verdict(UUID owner, UUID carrier) {
        if (owner == null) return Verdict.ADOPT;
        return owner.equals(carrier) ? Verdict.KEEP : Verdict.FOREIGN;
    }
}
