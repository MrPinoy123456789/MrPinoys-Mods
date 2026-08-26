package pocketdungeons;

import java.util.List;
import java.util.UUID;

public class KeystoneOfferTest {

    public static void main(String[] args) {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<String> themes = List.of("deepslate", "prismarine", "blackstone", "grove");
        List<String> expected = ThemeOfferMath.pick(owner, 12, themes);
        for (int i = 0; i < 100; i++) {
            check(ThemeOfferMath.pick(owner, 12, themes), expected, "stable repeat " + i);
        }
        check(expected.stream().distinct().count(), 3L, "three distinct doors");
        UUID different = UUID.fromString("10000000-0000-0000-0000-000000000001");
        if (ThemeOfferMath.pick(different, 12, themes).equals(expected)) {
            throw new AssertionError("different owners should differ");
        }
        System.out.println("KeystoneOfferTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
