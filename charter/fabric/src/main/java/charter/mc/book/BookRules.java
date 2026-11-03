package charter.mc.book;

import java.util.UUID;

/**
 * The pure rules of the Charter book, with no Minecraft types: whose a carried copy is. The plain Java tests run these.
 */
public final class BookRules {

    private BookRules() {
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
