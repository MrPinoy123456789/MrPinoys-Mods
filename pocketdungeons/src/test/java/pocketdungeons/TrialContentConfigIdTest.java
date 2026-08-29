package pocketdungeons;

public class TrialContentConfigIdTest {

    public static void main(String[] args) {
        check(TrialContent.configId(null, 1, false), "pocketdungeons:tier_1/normal", "null prefix");
        check(TrialContent.configId("crypt", 1, false), "pocketdungeons:crypt_tier_1/normal", "crypt prefix");
        check(TrialContent.configId("", 3, true), "pocketdungeons:tier_3/ominous", "blank prefix");
        check(TrialContent.configId("infestation", 2, false),
                "pocketdungeons:infestation_tier_2/normal", "infestation prefix");
        System.out.println("TrialContentConfigIdTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
