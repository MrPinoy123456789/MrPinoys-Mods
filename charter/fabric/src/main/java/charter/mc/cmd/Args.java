package charter.mc.cmd;

import charter.time.Durations;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typed argument formats that Brigadier has no type for. */
public final class Args {

    private static final Pattern PART = Pattern.compile("(\\d+)([dhms])");

    private Args() {
    }

    /** "90m", "2h", "3d", "1d12h30m" to milliseconds; -1 if it does not read. */
    public static long duration(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> n * Durations.DAY;
                case "h" -> n * Durations.HOUR;
                case "m" -> n * Durations.MINUTE;
                default -> n * Durations.SECOND;
            };
            end = m.end();
        }
        return end == s.length() && end > 0 ? total : -1;
    }
}
