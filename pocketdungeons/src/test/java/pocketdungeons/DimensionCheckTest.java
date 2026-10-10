package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Code audit 2026-10-10 (item 4): "is this the dungeon dimension" has one production answer,
 * {@link DungeonWorldRules#inDungeon}. This reads the main sources and fails when any file other than
 * {@code DungeonWorldRules} compares a dimension against {@code PocketDungeonsMod.DUNGEON_LEVEL} itself
 * ({@code .equals(...)}, {@code ==} or {@code !=}). Retrieving the level with
 * {@code server.getLevel(DUNGEON_LEVEL)} or teleporting to it is not a comparison and stays allowed.
 */
public class DimensionCheckTest {

    private static final Path SOURCES = Paths.get("src/main/java");
    private static final Pattern RAW = Pattern.compile(
            "\\.equals\\(\\s*PocketDungeonsMod\\.DUNGEON_LEVEL\\s*\\)"
                    + "|PocketDungeonsMod\\.DUNGEON_LEVEL\\s*\\.equals\\("
                    + "|[!=]=\\s*PocketDungeonsMod\\.DUNGEON_LEVEL\\b"
                    + "|PocketDungeonsMod\\.DUNGEON_LEVEL\\s*[!=]=");

    public static void main(String[] args) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        check(files.size() > 100, "found only " + files.size() + " source files; wrong working directory?");
        List<String> offenders = new ArrayList<>();
        int helperUses = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            String text = Files.readString(file);
            if (!name.equals("DungeonWorldRules.java")) {
                String[] lines = text.split("\r?\n");
                for (int i = 0; i < lines.length; i++) {
                    if (RAW.matcher(lines[i]).find()) {
                        offenders.add(file + ":" + (i + 1) + ": " + lines[i].trim());
                    }
                }
            }
            helperUses += text.split("DungeonWorldRules\\.inDungeon\\(", -1).length - 1;
        }
        check(offenders.isEmpty(), "raw comparison with DUNGEON_LEVEL; use DungeonWorldRules.inDungeon:\n  "
                + String.join("\n  ", offenders));
        check(helperUses >= 50, "expected the sites to use the helper, found only " + helperUses + " uses");
        System.out.println("DimensionCheckTest passed (" + helperUses + " helper uses)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
