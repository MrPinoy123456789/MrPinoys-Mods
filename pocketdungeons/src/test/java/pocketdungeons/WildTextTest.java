package pocketdungeons;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Wild lodestones task 1.3: the words players read. Short, no dash punctuation, the largest time unit only, and the
 * collapse line only in the last day.
 */
public class WildTextTest {

    private static final String EM_DASH = String.valueOf((char) 0x2014);

    public static void main(String[] args) throws Exception {
        durations();
        outsideBodies();
        constants();
        System.out.println("WildTextTest passed");
    }

    private static void durations() {
        eq(WildText.duration(0), "1s");
        eq(WildText.duration(-50), "1s");
        eq(WildText.duration(30), "30s");
        eq(WildText.duration(59), "59s");
        eq(WildText.duration(60), "1m");
        eq(WildText.duration(45 * 60), "45m");
        eq(WildText.duration(3599), "59m");
        eq(WildText.duration(3600), "1h");
        eq(WildText.duration(2 * 3600 + 1799), "2h");
        eq(WildText.duration(86399), "23h");
        eq(WildText.duration(86400), "1d");
        eq(WildText.duration(6L * 86400L + 5L * 3600L), "6d");
    }

    private static void outsideBodies() {
        long week = 7L * 86400L;
        eq(WildText.outsideBody("Iron. Deep.", WildState.Phase.READY, 0, 0, week), List.of("Iron. Deep.", "Ready."));
        eq(WildText.outsideBody("Iron. Deep.", WildState.Phase.IN_PROGRESS, 3, 0, week),
                List.of("Iron. Deep.", "In progress. 3 inside."));
        eq(WildText.outsideBody("Iron. Deep.", WildState.Phase.IN_PROGRESS, 0, 0, week),
                List.of("Iron. Deep.", "In progress."));
        eq(WildText.outsideBody("Iron. Deep.", WildState.Phase.CLEARED, 0, 7200, week),
                List.of("Iron. Deep.", "Cleared. Resets in 2h."));
        eq(WildText.outsideBody("Iron. Deep.", WildState.Phase.READY, 0, 0, 20 * 3600),
                List.of("Iron. Deep.", "Ready.", "Collapses in 20h."));
        eq(WildText.outsideBody("Iron. Deep.", WildState.Phase.READY, 0, 0, 86400 + 1).size(), 2);
        eq(WildText.outsideBody("Iron. Deep.", WildState.Phase.READY, 0, 0, 86400).size(), 3);
        eq(WildText.clearExitBody(2700), "Cleared. Resets in 45m.");
    }

    /** Every string constant is short and free of em dashes and spaced double hyphens. */
    private static void constants() throws Exception {
        int checked = 0;
        for (Field field : WildText.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String value = (String) field.get(null);
                check(value.length() <= 60, field.getName() + " is " + value.length() + " characters");
                noDashes(field.getName(), value);
                checked++;
            }
        }
        check(checked >= 10, "found only " + checked + " strings");
        for (String line : WildText.outsideBody("Iron. Deep.", WildState.Phase.CLEARED, 0, 7200, 3600)) {
            noDashes("outside body", line);
        }
    }

    private static void noDashes(String where, String value) {
        check(!value.contains(EM_DASH), where + " has an em dash");
        check(!value.contains((" " + "-" + "- ")), where + " has a spaced double hyphen");
    }

    private static void eq(Object actual, Object expected) {
        check(expected.equals(actual), "expected " + expected + " but was " + actual);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
