package charter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The shared pass and fail tally for the core suite. No JUnit, no server. */
public final class Check {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private Check() {
    }

    public static void check(String description, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(description);
        }
    }

    public static void eq(String description, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
        } else {
            failures.add(description + " (expected " + expected + ", got " + actual + ")");
        }
    }

    static int passed() {
        return passed;
    }

    static List<String> failures() {
        return failures;
    }
}
