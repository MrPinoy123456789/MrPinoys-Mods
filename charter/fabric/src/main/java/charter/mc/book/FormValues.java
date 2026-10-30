package charter.mc.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What a dialog form sends back. The submit button runs {@code /charter form <token> =<a> | <b> | <c>}: one
 * template, the inputs in order, joined by a bar. The leading {@code =} keeps the text from ever being empty,
 * which the command parser would refuse. Nothing here trusts what comes back; the page that built the form
 * checks every value again before it does anything.
 */
public final class FormValues {

    public static final String PREFIX = "=";
    public static final String SEPARATOR = " | ";

    private FormValues() {
    }

    /** The command a submit button runs, for inputs with these keys (the keys are the template's variables). */
    public static String template(String token, List<String> keys) {
        StringBuilder out = new StringBuilder("/charter form ").append(token).append(' ').append(PREFIX);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) out.append(SEPARATOR);
            out.append("$(").append(keys.get(i)).append(')');
        }
        return out.toString();
    }

    /** The values in order, trimmed, or empty when the count is not the one the page asked for. */
    public static Optional<List<String>> split(String raw, int expected) {
        if (raw == null || !raw.startsWith(PREFIX)) return Optional.empty();
        String[] parts = raw.substring(PREFIX.length()).split("\\|", -1);
        if (parts.length != expected) return Optional.empty();
        List<String> out = new ArrayList<>();
        for (String part : parts) out.add(part.strip());
        return Optional.of(out);
    }

    /** True for a value a form may carry: no bar (it is the separator) and no control characters. */
    public static boolean clean(String value) {
        if (value.indexOf('|') >= 0) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    public static OptionalInt integer(String value) {
        try {
            return OptionalInt.of(Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** A name as one command argument: quoted, with the quote and the backslash escaped. */
    public static String quote(String name) {
        return '"' + name.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
