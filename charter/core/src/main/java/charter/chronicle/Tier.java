package charter.chronicle;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a Chronicle line goes. The tier never decides whether history records an
 * event, only who is told about it (docs/2026-11-04-chronicle-design.md 3.2).
 */
public enum Tier {
    /** The whole server would turn its head: the realm lecterns, the book's home page, the feed. */
    REALM,
    /** The kingdom's own business: its keep lectern and its own book page. */
    KINGDOM,
    /** Recorded, never told outside the admin view. */
    QUIET;

    /** "realm", "kingdom", "quiet". */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A tier from a config word, in any case; anything else is empty. */
    public static Optional<Tier> parse(String word) {
        if (word == null) return Optional.empty();
        for (Tier t : values()) {
            if (t.word().equalsIgnoreCase(word.strip())) return Optional.of(t);
        }
        return Optional.empty();
    }
}
