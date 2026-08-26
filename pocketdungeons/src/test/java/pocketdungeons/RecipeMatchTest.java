package pocketdungeons;

import java.util.List;

public class RecipeMatchTest {

    public static void main(String[] args) {
        List<String> recipe = List.of("a", "b", "c");
        check(RecipeMatcher.matches(List.of("a", "b", "c"), recipe), true, "ordered match");
        check(RecipeMatcher.matches(List.of("a", "c", "b"), recipe), false, "wrong order");
        check(RecipeMatcher.matches(List.of("a", "b"), recipe), false, "short window");
        check(RecipeMatcher.matches(List.of("old", "a", "b", "c"), recipe), true, "tail match");
        System.out.println("RecipeMatchTest passed");
    }

    private static void check(boolean actual, boolean expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
