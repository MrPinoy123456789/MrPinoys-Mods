package pocketdungeons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * House rule scan for the terminal (spec 7 tests): nothing the terminal writes,
 * player-facing or not, may carry an em dash or a spaced double hyphen.
 */
public class TerminalSourceTest {

    public static void main(String[] args) throws IOException {
        for (String file : new String[]{
                "src/main/java/pocketdungeons/Terminal.java",
                "src/gametest/java/pocketdungeons/TerminalGameTest.java"}) {
            String text = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            check(!text.contains("—"), file + " has an em dash");
            check(!text.contains(" -- "), file + " has a spaced double hyphen");
        }
        System.out.println("TerminalSourceTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
