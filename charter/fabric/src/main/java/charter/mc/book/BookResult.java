package charter.mc.book;

/**
 * The line shown above a rebuilt page after a press is either a success (green) or a refusal (red). Pages pass it as a
 * plain string, so a success is marked with a private-use character that never reaches a player: {@link #ok} adds it,
 * {@link #isOk} reads it and {@link #text} strips it. A string with no mark is a refusal.
 */
public final class BookResult {

    private static final char OK = '';

    private BookResult() {
    }

    public static String ok(String line) {
        return line == null ? null : OK + line;
    }

    public static boolean isOk(String line) {
        return line != null && !line.isEmpty() && line.charAt(0) == OK;
    }

    public static String text(String line) {
        return isOk(line) ? line.substring(1) : line;
    }

    /** The refusal when the engine gave one, otherwise the success line. */
    public static String of(String refusal, String done) {
        return refusal != null ? refusal : ok(done);
    }
}
