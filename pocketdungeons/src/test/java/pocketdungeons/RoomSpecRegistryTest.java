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
 * Code audit 2026-10-10: {@code RoomTemplateGenerator.specs()} is a hand written list of
 * {@code XxxSpecs.list()} calls, one per room family. A family class that is written but never added
 * to it compiles, passes every other test, and its rooms are never generated. This reads the sources
 * and fails if a class that declares {@code static List<RoomSpec> list()} is missing from the list.
 */
public class RoomSpecRegistryTest {

    private static final Path SOURCES = Paths.get("src/main/java/pocketdungeons");
    private static final Pattern FAMILY = Pattern.compile("static\\s+List<RoomSpec>\\s+list\\(\\)");

    public static void main(String[] args) throws IOException {
        String generator = Files.readString(SOURCES.resolve("RoomTemplateGenerator.java"));
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        List<String> families = new ArrayList<>();
        for (Path file : files) {
            if (FAMILY.matcher(Files.readString(file)).find()) {
                String name = file.getFileName().toString().replace(".java", "");
                families.add(name);
                check(generator.contains("specs.addAll(" + name + ".list())"),
                        name + " declares a room family but RoomTemplateGenerator.specs() never adds it");
            }
        }
        check(families.size() >= 20, "found only " + families.size() + " room families: " + families);
        System.out.println("RoomSpecRegistryTest passed (" + families.size() + " families)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
