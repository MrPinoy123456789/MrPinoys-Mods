package charter.mc;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain Java tests of the fabric module: no JUnit, no server. Each test class
 * has a static {@code run()} and is listed here; a class missing from this list
 * never runs. Exits non-zero on any failure so {@code check} fails.
 */
public final class FabricSuite {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        ConfigTest.run();
        StoreTest.run();
        RegistryTest.run();
        RuntimeTest.run();
        LandFixturesTest.run();
        GranaryTest.run();
        JusticeTest.run();
        ChronicleBookTest.run();
        ScenarioFilesTest.run();

        System.out.println("fabric: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAIL " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    static void check(String description, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
