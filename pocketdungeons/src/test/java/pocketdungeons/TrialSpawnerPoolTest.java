package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * PD-213 (playtest 2026-10-16-1): the enderman is the only trial mob that
 * teleports out of its room, so a released stray could survive on a roof and
 * stay audible after the spawner's gate cleared. Rather than widening the
 * stray sweep, enderman was removed from every trial spawner config. This
 * holds the pools to that, so a regenerated or hand-edited file cannot bring
 * the enderman back. {@code RestlessRules} keeps its own enderman mapping;
 * that is not a trial pool.
 */
public class TrialSpawnerPoolTest {

    private static final Path SPAWNERS = Paths.get("src/main/resources/data/pocketdungeons/trial_spawner");

    public static void main(String[] args) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SPAWNERS)) {
            files = walk.filter(p -> p.toString().endsWith(".json")).toList();
        }
        int checked = 0;
        for (Path file : files) {
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(file)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            JsonElement potentials = root.get("spawn_potentials");
            if (potentials == null || !potentials.isJsonArray()) {
                continue;
            }
            for (JsonElement entry : potentials.getAsJsonArray()) {
                JsonElement entity = entry.getAsJsonObject()
                        .getAsJsonObject("data").getAsJsonObject("entity").get("id");
                checked++;
                check(!"minecraft:enderman".equals(entity.getAsString()),
                        file + " still spawns enderman (PD-213)");
            }
        }
        check(checked > 50, "found only " + checked + " spawn potentials");
        System.out.println("TrialSpawnerPoolTest passed (" + checked + " spawn potentials, no enderman)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
