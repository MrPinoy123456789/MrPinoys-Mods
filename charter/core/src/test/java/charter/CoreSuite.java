package charter;

/**
 * The core test suite, run by {@code gradlew coreTest}. Each test class has a
 * static {@code run()} and is listed here, one line each; a class missing from
 * this list never runs. Exits non-zero on any failure so {@code check} fails.
 */
public final class CoreSuite {

    private CoreSuite() {
    }

    public static void main(String[] args) {
        charter.config.KnobsTest.run();
        charter.gov.GovernanceTest.run();
        charter.grant.AuthorityTest.run();
        charter.land.LandTest.run();
        charter.food.FoodTest.run();
        charter.chronicle.ChronicleTest.run();
        charter.siege.SiegeTest.run();
        charter.contract.ContractTest.run();

        System.out.println("core: " + Check.passed() + " passed, " + Check.failures().size() + " failed");
        for (String failure : Check.failures()) {
            System.out.println("  FAIL " + failure);
        }
        if (!Check.failures().isEmpty()) {
            System.exit(1);
        }
    }
}
