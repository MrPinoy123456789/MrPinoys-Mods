package chatdonkey.core;

/**
 * Cleans a line that came from outside the server before the donkey says it
 * (SPEC.md section 9, {@code /donkey say}).
 *
 * <p>The eventual source is Twitch chat. However well a bridge moderates, by the
 * time a string reaches here it is untrusted input, and it is about to be
 * rendered into every nearby player's chat window under a name they trust.
 *
 * <p>Three rules, in order of what they prevent:
 * <ol>
 *   <li><b>Section signs are stripped.</b> Minecraft's formatting escape. Left
 *       in, a viewer could colour their text, hide it, or -- much worse -- forge
 *       a convincing second {@code <Duncan>} prefix and put words in another
 *       player's mouth.</li>
 *   <li><b>Control characters are stripped</b>, newlines included, so one line
 *       cannot become several.</li>
 *   <li><b>Length is capped.</b> A wall of text is a denial-of-chat, and the
 *       animalese would blip for its entire length.</li>
 * </ol>
 *
 * <p>The failure direction is a duller donkey, never a dropped guard: anything
 * that survives sanitising is safe to print, and anything that does not survive
 * yields {@code ""}, which callers treat as "say nothing".
 */
public final class ChatLine {

    /** Long enough for a real sentence, short enough not to be a wall. */
    public static final int MAX_LENGTH = 120;

    private ChatLine() {}

    /** @return the cleaned line, or {@code ""} if nothing usable survived */
    public static String sanitise(String raw) {
        if (raw == null) {
            return "";
        }

        StringBuilder clean = new StringBuilder(Math.min(raw.length(), MAX_LENGTH));
        for (int i = 0; i < raw.length() && clean.length() < MAX_LENGTH; i++) {
            char c = raw.charAt(i);

            // The formatting escape. The code letter that follows it goes too --
            // dropping only the section sign would leave a stray "c" or "r"
            // littering the line, and would let a formatting-only string survive
            // as visible garbage instead of being refused outright.
            if (c == '§') {
                i++;
                continue;
            }
            // Newlines, tabs, and anything else non-printable. A space keeps
            // words apart rather than gluing them together.
            if (Character.isISOControl(c)) {
                clean.append(' ');
                continue;
            }
            clean.append(c);
        }

        return collapseSpaces(clean.toString()).trim();
    }

    /** Whether a line is safe and worth saying at all. */
    public static boolean isUsable(String raw) {
        return !sanitise(raw).isEmpty();
    }

    private static String collapseSpaces(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isSpace = c == ' ';
            if (isSpace && lastWasSpace) {
                continue;
            }
            out.append(c);
            lastWasSpace = isSpace;
        }
        return out.toString();
    }
}
