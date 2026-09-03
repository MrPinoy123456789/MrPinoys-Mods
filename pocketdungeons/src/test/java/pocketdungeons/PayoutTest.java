package pocketdungeons;

/**
 * Regression for {@link Payout#substitute} (M44.4): the {@code %player%},
 * {@code %level%}, and {@code %chests%} placeholder fill in a configured
 * {@code payoutCommand} template. {@link Payout#deliver} and {@link
 * Payout#runPayoutCommand} themselves need a {@code ServerPlayer} this
 * headless suite does not have, so this covers only the pure substitution
 * step, the same split {@code RerollStation}/{@code CubeStation} use to keep
 * their marker-reading pure.
 */
public class PayoutTest {

    public static void main(String[] args) {
        testAllThreePlaceholders();
        testRepeatedPlaceholder();
        testMissingPlaceholdersLeftAlone();
        testNoPlaceholdersAtAll();

        System.out.println("PayoutTest passed");
    }

    private static void testAllThreePlaceholders() {
        String result = Payout.substitute("give %player% diamond 1, level %level%, chests %chests%",
                "Steve", 12, 3);
        check("give Steve diamond 1, level 12, chests 3", result);
    }

    private static void testRepeatedPlaceholder() {
        String result = Payout.substitute("say %player% did it. %player% is the best.", "Alex", 1, 0);
        check("say Alex did it. Alex is the best.", result);
    }

    private static void testMissingPlaceholdersLeftAlone() {
        String result = Payout.substitute("give %player% diamond 1", "Steve", 5, 2);
        check("give Steve diamond 1", result);
    }

    private static void testNoPlaceholdersAtAll() {
        String result = Payout.substitute("say hello world", "Steve", 5, 2);
        check("say hello world", result);
    }

    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but was '" + actual + "'");
        }
    }
}
