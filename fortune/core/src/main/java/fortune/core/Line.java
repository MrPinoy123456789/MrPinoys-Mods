package fortune.core;

import java.util.Locale;

/**
 * The result lines of the paytable, lowest first. The order matters: a reward floor is "this line or better", and
 * {@link #tripleOf(int)} maps a symbol group onto the four three of a kind lines in order.
 */
public enum Line {
    NO_MATCH("No match"),
    PAIR("A pair"),
    LOW_TRIPLE("Three of a kind"),
    MID_TRIPLE("Three of a kind"),
    DIAMONDS("Three Diamonds"),
    CROWNS("Three Crowns");

    /** The first three of a kind line; groups 0 to 3 map onto it and the three after it. */
    public static final Line FIRST_TRIPLE = LOW_TRIPLE;

    private final String label;

    Line(String label) {
        this.label = label;
    }

    /** A plain name for chat and lore. The two generic triples are named by their symbol at the call site. */
    public String label() {
        return label;
    }

    public boolean isTriple() {
        return ordinal() >= FIRST_TRIPLE.ordinal();
    }

    public boolean atLeast(Line other) {
        return ordinal() >= other.ordinal();
    }

    /** The three of a kind line a symbol group pays. Group 0 is the lowest. */
    public static Line tripleOf(int group) {
        int index = FIRST_TRIPLE.ordinal() + group;
        if (group < 0 || index >= values().length) throw new IllegalArgumentException("No triple line for group " + group);
        return values()[index];
    }

    /** The group a three of a kind line pays for, or -1 for no match and pair. */
    public int group() {
        return isTriple() ? ordinal() - FIRST_TRIPLE.ordinal() : -1;
    }

    /** Reads a config name such as {@code "three_low"}, {@code "pair"} or {@code "LOW_TRIPLE"}. */
    public static Line parse(String name) {
        String key = name.trim().toUpperCase(Locale.ROOT);
        return valueOf(key);
    }
}
