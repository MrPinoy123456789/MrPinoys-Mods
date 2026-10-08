package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PD-171: a rubble plug may never cut off a room that holds a trial spawner, however the
 * spawner got there. {@link RoomSelector#ENCOUNTER_ROOMS} names the rooms whose handler calls
 * {@code TrialContent.applyEncounter}; this reads the sources and fails if one is missing, so a
 * new combat room cannot silently reopen the soft-lock (the Barred Vault did, in playtest
 * 2026-10-07-2).
 */
public class RubbleRulesTest {

    private static final Path SOURCES = Paths.get("src/main/java/pocketdungeons");
    private static final Pattern CALL = Pattern.compile("applyEncounter\\([^;]*?\"([a-z_]+)\",\\s*(?:true|false)\\)");

    public static void main(String[] args) throws IOException {
        Set<String> found = new HashSet<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            Matcher m = CALL.matcher(Files.readString(file));
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        check(found.size() >= 10, "found only " + found.size() + " encounter rooms in the sources: " + found);
        for (String room : found) {
            check(RoomSelector.hostsEncounter(room, null),
                    room + " calls applyEncounter but is not in RoomSelector.ENCOUNTER_ROOMS");
        }
        for (String room : RoomSelector.ENCOUNTER_ROOMS) {
            check(found.contains(room), room + " is in ENCOUNTER_ROOMS but no handler applies an encounter for it");
        }
        check(RoomSelector.hostsEncounter("hall_corner", "barred_vault"), "a room is matched by its content id too");
        check(!RoomSelector.hostsEncounter("hall_corner", null), "a plain hall holds no encounter");
        System.out.println("RubbleRulesTest passed (" + found.size() + " encounter rooms)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
