package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PD-201: state keyed by a cell origin must be dropped whenever the cell is cleared, not only when the
 * whole instance is torn down. Every class that declares a cell keyed {@code static void clear(BlockPos)}
 * has to be called from {@code InstanceTeardown.forgetCellState}, and both {@code teardown} and
 * {@code Instances.clearCells} (how a floor ends) have to call that method.
 */
public class CellStateForgetTest {

    private static final Path SOURCES = Paths.get("src/main/java/pocketdungeons");
    private static final Pattern CELL_CLEAR = Pattern.compile("static\\s+void\\s+clear\\(\\s*BlockPos\\s+\\w+\\s*\\)");

    public static void main(String[] args) throws IOException {
        String teardown = Files.readString(SOURCES.resolve("InstanceTeardown.java"));
        int start = teardown.indexOf("static void forgetCellState(");
        check(start >= 0, "InstanceTeardown.forgetCellState is missing");
        String body = teardown.substring(start, teardown.indexOf("\n    }", start));

        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        List<String> owners = new ArrayList<>();
        for (Path file : files) {
            Matcher m = CELL_CLEAR.matcher(Files.readString(file));
            if (m.find()) {
                String name = file.getFileName().toString().replace(".java", "");
                owners.add(name);
                check(body.contains(name + ".clear("),
                        name + " keeps cell keyed state but forgetCellState never clears it");
            }
        }
        check(owners.size() >= 6, "found only " + owners.size() + " cell keyed clears: " + owners);

        check(teardown.contains("forgetCellState(cellOrigin)"), "teardown must call forgetCellState per cell");
        String instances = Files.readString(SOURCES.resolve("Instances.java"));
        int clearCells = instances.indexOf("static void clearCells(");
        check(clearCells >= 0, "Instances.clearCells is missing");
        String clearBody = instances.substring(clearCells, instances.indexOf("\n    }", clearCells));
        check(clearBody.contains("InstanceTeardown.forgetCellState("),
                "Instances.clearCells (a floor ending) must call InstanceTeardown.forgetCellState");
        System.out.println("CellStateForgetTest passed (" + owners.size() + " cell keyed clears: " + owners + ")");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
