package pocketdungeons;

import java.util.List;

public class DungeonLogTest {

    public static void main(String[] args) {
        List<String> recent = List.of();
        recent = ThemeHistory.push(recent, "a");
        recent = ThemeHistory.push(recent, "b");
        recent = ThemeHistory.push(recent, "c");
        check(recent, List.of("a", "b", "c"), "three in order");
        recent = ThemeHistory.push(recent, "d");
        check(recent, List.of("b", "c", "d"), "push and truncate");
        check(ThemeHistory.push(recent, null), recent, "null ignored");
        System.out.println("DungeonLogTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
